package com.tangluobo.tomato.rdp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.tangluobo.tomato.rdp.rdp5.VChannel;

/**
 * rdpsnd音频重定向虚拟通道（MS-RDPEA客户端实现）。
 *
 * javardp 3.0.0库本身没有rdpsnd通道实现（仅有cliprdr与DisplayControl）。
 * 仅在connect-initial PDU里声明"rdpsnd"通道名不足以让服务器推送音频：
 * 必须实现完整的格式协商、训练应答、两段式WAV接收与WAVECONFIRM流控应答，
 * 服务器才会把远程声音通过rdpsnd通道重定向到客户端。
 *
 * 协议要点（与rdesktop master的rdpsnd.c逐字段对齐，该实现经Windows服务器
 * 多年验证；同时交叉核对FreeRDP与MS-RDPEA规范）：
 * <ul>
 * <li>PDU头4字节：msgType(1) + bPad(1) + BodySize(2, 小端)</li>
 * <li>SNDC_FORMATS(0x07)：服务器连接后主动下发其格式列表，客户端回复
 *     客户端根据本地能力回复支持的格式，并声明wVersion=8。</li>
 * <li>SNDC_TRAINING(0x06)：回显wTimeStamp+wPackSize</li>
 * <li>SNDC_WAVE(0x02)两段式：BodySize=8字节wave头+全部音频数据。
 *     第一个通道消息=PDU头+wave头(8)+前4字节音频；第二个通道消息
 *     不含PDU头，其前4字节是重复的(timestamp+formatNo)垃圾数据，必须跳过
 *     （rdesktop注释："Microsoft's server is so broken"）。</li>
 * <li>SNDC_WAVECONFIRM(0x05)：每块音频必须应答，否则服务器停止发送
 *     （流控）。本实现入队后立即应答原始tick，播放由独立线程异步完成，
 *     避免SourceDataLine阻塞RDP主循环导致画面卡顿。</li>
 * </ul>
 */
public class RdpsndChannel extends VChannel {

    private static final Logger logger = Logger.getLogger(RdpsndChannel.class.getName());

    // ===== MS-RDPEA 2.2.2 消息类型 =====
    private static final int SNDC_CLOSE = 0x01;
    private static final int SNDC_WAVE = 0x02;
    private static final int SNDC_SETVOLUME = 0x03;
    private static final int SNDC_SETPITCH = 0x04;
    private static final int SNDC_WAVECONFIRM = 0x05;
    private static final int SNDC_TRAINING = 0x06;
    private static final int SNDC_FORMATS = 0x07;
    private static final int SNDC_QUALITYMODE = 0x0C;
    private static final int SNDC_WAVE2 = 0x0D;

    /** TSSNDCAPS能力标志（SNDC_FORMATS.dwFlags） */
    private static final int TSSNDCAPS_ALIVE = 0x0001;
    private static final int TSSNDCAPS_VOLUME = 0x0002;

    /** WAVE_FORMAT_PCM */
    private static final int WAVE_FORMAT_PCM = 0x0001;

    /**
     * 客户端声明的协议版本。CHANNEL_VERSION_WIN_MAX=8，与mstsc/FreeRDP一致。
     * 注意：rdesktop使用的v2协商在Windows 10+服务器上会被rdpsnd服务端
     * 直接忽略（公开已知问题），表现为服务器对SNDC_FORMATS零响应。
     */
    private static final int CLIENT_VERSION = 8;

    /** 协商版本达到此值后必须发送Quality Mode PDU（MS-RDPEA 3.2.5.1） */
    private static final int VERSION_REQUIRES_QUALITY_MODE = 6;

    /** MS-RDPEA: DYNAMIC_QUALITY=0（1是MEDIUM，2是HIGH） */
    private static final int QUALITY_MODE_DYNAMIC = 0;

    /** 客户端最大可接受格式数（与rdesktop MAX_FORMATS一致） */
    private static final int MAX_FORMATS = 10;

    /** 播放队列容量（块），超出丢弃最旧数据以保证低延迟 */
    private static final int QUEUE_CAPACITY = 64;

    /** 无新音频达到该时长后释放输出设备，避免空转音频线周期性产生杂音。 */
    private static final int PLAYBACK_IDLE_TIMEOUT_MILLIS = 1000;

    /** 首次协商完成后等待首个音频样本的时限。 */
    private static final int FIRST_WAVE_TIMEOUT_SECONDS = 2;

    /** 协商成功后服务器将使用的格式列表（按服务器优先级排序） */
    private final List<AudioDef> negotiatedFormats = new ArrayList<>();

    /** 首个音频样本是否已到达；用于避免对正常播放会话重复重协商。 */
    private volatile boolean receivedFirstWave;
    /** 启动恢复只允许执行一次，避免服务端不支持时产生报文循环。 */
    private volatile boolean startupRecoveryAttempted;
    private volatile ScheduledFuture<?> firstWaveRecovery;
    private final ScheduledExecutorService recoveryTimer = Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rdpsnd-first-wave-recovery");
                t.setDaemon(true);
                return t;
            });

    // ===== PDU级重组状态（一个通道消息可能含多个PDU；PDU可能跨通道消息） =====
    /** 进行中PDU的msgType（pendingBodySize==0时无意义） */
    private int pendingMsgType;
    /** 进行中PDU的BodySize（0表示无进行中PDU） */
    private int pendingBodySize;
    /** 进行中PDU的body累积缓冲 */
    private final ByteArrayOutputStream pendingBody = new ByteArrayOutputStream();

    // ===== 播放 =====
    private final BlockingQueue<AudioChunk> audioQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** 让播放线程关闭SourceDataLine的控制消息。 */
    private static final AudioChunk CLOSE_PLAYBACK = new AudioChunk(null, new byte[0]);
    private final Thread playThread;
    private volatile boolean closed;
    /** 播放线程持有的SourceDataLine（仅播放线程访问） */
    private SourceDataLine line;
    /** 当前line打开的格式（仅播放线程访问） */
    private AudioDef lineFormat;

    public RdpsndChannel() {
        playThread = new Thread(this::playLoop, "rdpsnd-player");
        playThread.setDaemon(true);
        playThread.start();
    }

    @Override
    public String name() {
        return "rdpsnd";
    }

    @Override
    public int flags() {
        // INITIALIZED|ENCRYPT_RDP：与rdesktop/mstsc的rdpsnd通道一致。
        // 不含SHOW_PROTOCOL/COMPRESS，避免服务器端通道数据封装差异；
        // 该值同时决定VChannel.send_packet写入chunk头的CHANNEL_FLAG_SHOW_PROTOCOL位。
        return 0xC0000000;
    }

    /**
     * 处理服务器下发的rdpsnd通道消息。
     * 一个通道消息内可能包含多个PDU；SNDC_WAVE的完整数据
     * 会分两个通道消息到达（见类注释）。
     * 所有异常均被捕获记录：音频故障不应导致整个RDP会话断开。
     */
    @Override
    public void process(Packet packet) {
        try {
            int avail = packet.getEnd() - packet.getPosition();
            if (avail <= 0) {
                return;
            }
            byte[] data = new byte[avail];
            packet.copyToByteArray(data, 0, packet.getPosition(), avail);
            // 诊断：记录任何到达rdpsnd通道的数据（用于确认服务器是否在推送音频）
            StringBuilder hex = new StringBuilder();
            int dumpLen = Math.min(data.length, 16);
            for (int i = 0; i < dumpLen; i++) {
                hex.append(String.format("%02x ", data[i]));
            }
            int msgType = data[0] & 0xFF;
            String message = "rdpsnd: 收到通道数据 " + data.length + "字节: " + hex.toString().trim();
            if (msgType == SNDC_WAVE || msgType == SNDC_WAVE2) {
                logger.finest(message);
            } else {
                logger.info(message);
            }
            processStream(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "rdpsnd: 处理消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * PDU级状态机（对应rdesktop rdpsnd_process的重组循环）。
     */
    private void processStream(byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            if (pendingBodySize == 0) {
                if (data.length - offset < 4) {
                    logger.warning("rdpsnd: PDU头跨通道消息截断，丢弃" + (data.length - offset) + "字节");
                    return;
                }
                pendingMsgType = data[offset] & 0xFF;
                offset += 2; // msgType + bPad
                pendingBodySize = le16(data, offset);
                offset += 2;
                pendingBody.reset();
                if (pendingBodySize == 0) {
                    handlePdu(pendingMsgType, EMPTY_BODY);
                }
            } else {
                int local = offset;
                // 只有SNDC_WAVE是两段式：WaveInfo中含首4字节音频，
                // 后续Wave PDU的前4字节是bPad，需跳过。SNDC_WAVE2是单一PDU，
                // 元数据后紧跟音频数据，不能跳过任何字节。
                int waveInfoLen = waveInfoLength(pendingMsgType);
                int take;
                if (waveInfoLen > 0 && pendingBody.size() < waveInfoLen) {
                    // 先凑满waveInfo长度，避免一次跨越垃圾判断边界
                    take = Math.min(Math.min(pendingBodySize - pendingBody.size(),
                            data.length - local), waveInfoLen - pendingBody.size());
                } else if (waveInfoLen > 0 && pendingBody.size() == waveInfoLen
                        && data.length - local >= 4) {
                    local += 4; // 吃掉4字节垃圾数据
                    take = Math.min(pendingBodySize - pendingBody.size(), data.length - local);
                } else {
                    take = Math.min(pendingBodySize - pendingBody.size(), data.length - local);
                }
                if (take > 0) {
                    pendingBody.write(data, local, take);
                }
                offset = local + Math.max(take, 0);
                if (pendingBody.size() >= pendingBodySize) {
                    handlePdu(pendingMsgType, pendingBody.toByteArray());
                    pendingBody.reset();
                    pendingBodySize = 0;
                }
            }
        }
    }

    /**
     * SNDC_WAVE的"wave头+首4字节数据"长度（12）。
     * SNDC_WAVE2及其余消息都是单一PDU，返回0（无两段式特殊处理）。
     */
    private static int waveInfoLength(int msgType) {
        return msgType == SNDC_WAVE ? 12 : 0;
    }

    private static final byte[] EMPTY_BODY = new byte[0];

    private void handlePdu(int msgType, byte[] body) {
        try {
            switch (msgType) {
                case SNDC_FORMATS:
                    handleFormats(body);
                    break;
                case SNDC_TRAINING:
                    handleTraining(body);
                    break;
                case SNDC_WAVE:
                    handleWave(body, 8);
                    break;
                case SNDC_WAVE2:
                    handleWave(body, 12);
                    break;
                case SNDC_CLOSE:
                    // 服务器关闭音频流：排空并关闭播放管线
                    closePlayback();
                    logger.info("rdpsnd: 服务器关闭音频流");
                    break;
                case SNDC_SETVOLUME:
                case SNDC_SETPITCH:
                default:
                    logger.fine("rdpsnd: 忽略消息 msgType=0x" + Integer.toHexString(msgType)
                            + " body=" + body.length + "字节");
                    break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "rdpsnd: 处理PDU(msgType=0x"
                    + Integer.toHexString(msgType) + ")失败: " + e.getMessage(), e);
        }
    }

    /**
     * 服务器格式协商（SNDC_FORMATS，服务器主动下发）。
     * 布局：dwFlags(4)+dwVolume(4)+dwPitch(4)+wDGramPort(2)
     * +wNumberOfFormats(2,LE)+cLastBlockConfirmed(1)+wVersion(2,LE)+bPad(1)
     * +ServerFormats[]（每项18字节+cbSize附加数据）。
     */
    private synchronized void handleFormats(byte[] body) throws RdesktopException, java.io.IOException {
        if (body.length < 20) {
            logger.warning("rdpsnd: SNDC_FORMATS消息过短(" + body.length + "字节)");
            return;
        }
        int count = le16(body, 14);
        int serverVersion = le16(body, 17);
        int negotiatedVersion = Math.min(serverVersion, CLIENT_VERSION);
        negotiatedFormats.clear();
        int pos = 20;
        for (int i = 0; i < count && pos + 18 <= body.length; i++) {
            int tag = le16(body, pos);
            int channels = le16(body, pos + 2);
            int samplesPerSec = le32(body, pos + 4);
            int avgBytesPerSec = le32(body, pos + 8);
            int blockAlign = le16(body, pos + 12);
            int bits = le16(body, pos + 14);
            int cbSize = le16(body, pos + 16);
            pos += 18 + cbSize;
            if (tag == WAVE_FORMAT_PCM && (bits == 8 || bits == 16)
                    && (channels == 1 || channels == 2) && samplesPerSec > 0) {
                // 补齐avgBytesPerSec/blockAlign（个别服务器下发0）
                int calcAlign = channels * bits / 8;
                if (blockAlign <= 0) {
                    blockAlign = calcAlign;
                }
                if (avgBytesPerSec <= 0) {
                    avgBytesPerSec = samplesPerSec * blockAlign;
                }
                negotiatedFormats.add(new AudioDef(tag, channels, samplesPerSec,
                        avgBytesPerSec, blockAlign, bits));
                if (negotiatedFormats.size() >= MAX_FORMATS) {
                    break;
                }
            }
        }
        logger.info("rdpsnd: 服务器格式协商 version=" + serverVersion
                + ", 格式数=" + count + ", 可播放PCM格式=" + negotiatedFormats.size()
                + ", 协商版本=" + negotiatedVersion);
        // 所有虚拟通道都共用同一个Transport。格式回复和Quality Mode必须连续写出，
        // 否则可能与剪贴板或输入PDU交错，令服务端重新进入音频初始化。
        sendWithCommLock(() -> {
            sendClientFormats();
            if (negotiatedVersion >= VERSION_REQUIRES_QUALITY_MODE) {
                sendQualityMode();
            }
        });
        scheduleFirstWaveRecovery(negotiatedVersion);
    }

    /**
     * Quality Mode PDU（SNDC_QUALITYMODE）：v7+协商后客户端必须发送，
     * 服务器据此按声明的质量模式推送音频（MS-RDPEA 2.2.3.6）。
     * body: wQualityMode(2,LE) + reserved(2)。
     */
    private void sendQualityMode() throws RdesktopException, java.io.IOException {
        Packet p = new Packet(8);
        p.set8(SNDC_QUALITYMODE);
        p.set8(0);
        p.setLittleEndian16(4);
        p.setLittleEndian16(QUALITY_MODE_DYNAMIC);
        p.setLittleEndian16(0);
        p.markEnd();
        send_packet(p);
        logger.info("rdpsnd: 已发送Quality Mode(动态)，v8协商完成");
    }

    /**
     * 回复客户端格式列表（SNDC_FORMATS）。
     * 整数字段均按协议使用小端编码。
     */
    private void sendClientFormats() throws RdesktopException, java.io.IOException {
        int bodySize = 20 + 18 * negotiatedFormats.size();
        Packet p = new Packet(4 + bodySize);
        p.set8(SNDC_FORMATS);
        p.set8(0);
        p.setLittleEndian16(bodySize);
        p.setLittleEndian32(TSSNDCAPS_ALIVE | TSSNDCAPS_VOLUME); // dwFlags
        p.setLittleEndian32(0xFFFFFFFF); // dwVolume（最大音量）
        p.setLittleEndian32(0); // dwPitch
        p.setLittleEndian16(0); // wDGramPort（不使用UDP）
        p.setLittleEndian16(negotiatedFormats.size());
        p.set8(0); // cLastBlockConfirmed
        p.setLittleEndian16(CLIENT_VERSION); // wVersion
        p.set8(0); // bPad
        for (AudioDef f : negotiatedFormats) {
            p.setLittleEndian16(f.tag);
            p.setLittleEndian16(f.channels);
            p.setLittleEndian32(f.samplesPerSec);
            p.setLittleEndian32(f.avgBytesPerSec);
            p.setLittleEndian16(f.blockAlign);
            p.setLittleEndian16(f.bits);
            p.setLittleEndian16(0); // cbSize
        }
        p.markEnd();
        send_packet(p);
        logger.info("rdpsnd: 已回复客户端格式（" + negotiatedFormats.size() + "个PCM格式, version="
                + CLIENT_VERSION + "）");
    }

    /**
     * 训练包（SNDC_TRAINING）：回显wTimeStamp与wPackSize，
     * 服务器据此测量往返延迟后开始推送音频。
     */
    private void handleTraining(byte[] body) throws RdesktopException, java.io.IOException {
        if (body.length < 4) {
            return;
        }
        int tick = le16(body, 0);
        int packsize = le16(body, 2);
        Packet p = new Packet(8);
        p.set8(SNDC_TRAINING);
        p.set8(0);
        p.setLittleEndian16(4);
        p.setLittleEndian16(tick);
        p.setLittleEndian16(packsize);
        p.markEnd();
        sendWithCommLock(() -> send_packet(p));
        logger.info("rdpsnd: 训练包已应答 tick=" + tick + " packsize=" + packsize);
    }

    /**
     * 音频数据（SNDC_WAVE/SNDC_WAVE2）。
     * body=wave头(waveHeaderLen)+完整音频数据；立即应答WAVECONFIRM
     * 保证服务器持续发送，播放异步进行。
     */
    private void handleWave(byte[] body, int waveHeaderLen) throws RdesktopException, java.io.IOException {
        if (body.length < waveHeaderLen + 4) {
            logger.warning("rdpsnd: WAVE消息过短(" + body.length + "字节)");
            return;
        }
        int tick = le16(body, 0);
        int formatNo = le16(body, 2);
        int blockNo = body[4] & 0xFF;
        receivedFirstWave = true;
        cancelFirstWaveRecovery();
        if (formatNo < negotiatedFormats.size()) {
            byte[] waveData = Arrays.copyOfRange(body, waveHeaderLen, body.length);
            enqueue(negotiatedFormats.get(formatNo), waveData);
        } else {
            logger.warning("rdpsnd: 无效格式索引 " + formatNo + "（已协商"
                    + negotiatedFormats.size() + "个格式）");
        }
        sendWaveConfirm(tick, blockNo);
    }

    /**
     * 播放确认（SNDC_WAVECONFIRM）：wTimeStamp(2,LE)+cConfirmedBlockNo(1)+bPad(1)。
     * 服务器流控依赖此应答，缺失会导致服务器停止发送音频。
     */
    private void sendWaveConfirm(int tick, int blockNo) throws RdesktopException, java.io.IOException {
        Packet p = new Packet(8);
        p.set8(SNDC_WAVECONFIRM);
        p.set8(0);
        p.setLittleEndian16(4);
        p.setLittleEndian16(tick);
        p.set8(blockNo);
        p.set8(0);
        p.markEnd();
        sendWithCommLock(() -> send_packet(p));
    }

    /** 要发送的rdpsnd PDU动作。 */
    @FunctionalInterface
    private interface ChannelSendAction {
        void send() throws RdesktopException, java.io.IOException;
    }

    /**
     * 将一个或一组rdpsnd PDU作为不可交错的传输操作发送。
     * Secure/Transport并不保证多线程write原子性；剪贴板、输入和音频回调可并发发生。
     */
    private void sendWithCommLock(ChannelSendAction action)
            throws RdesktopException, java.io.IOException {
        try {
            state.getCommLock().acquire();
            try {
                action.send();
            } finally {
                state.getCommLock().release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RdesktopException("发送rdpsnd PDU时被中断", e);
        }
    }

    /**
     * 某些 Windows 音频栈在 RDP 连接时已有媒体流正在播放，会完成首轮协商却不
     * 立刻绑定该流。若没有任何 Wave 数据到达，重发一次已协商的客户端能力以请求
     * 服务端重新激活 rdpsnd；仅尝试一次，正常会话不会走到这里。
     */
    private synchronized void scheduleFirstWaveRecovery(int negotiatedVersion) {
        if (closed || receivedFirstWave || startupRecoveryAttempted) {
            return;
        }
        cancelFirstWaveRecovery();
        firstWaveRecovery = recoveryTimer.schedule(() -> {
            synchronized (RdpsndChannel.this) {
                if (closed || receivedFirstWave || startupRecoveryAttempted) {
                    return;
                }
                startupRecoveryAttempted = true;
                try {
                    logger.info("rdpsnd: 首次协商后未收到音频数据，重发一次能力确认以激活当前音频流");
                    sendWithCommLock(() -> {
                        sendClientFormats();
                        if (negotiatedVersion >= VERSION_REQUIRES_QUALITY_MODE) {
                            sendQualityMode();
                        }
                    });
                } catch (Exception e) {
                    logger.log(Level.WARNING, "rdpsnd: 首流恢复协商失败: " + e.getMessage(), e);
                }
            }
        }, FIRST_WAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelFirstWaveRecovery() {
        if (firstWaveRecovery != null) {
            firstWaveRecovery.cancel(false);
            firstWaveRecovery = null;
        }
    }

    // =====================================================================
    // 播放（独立线程，避免SourceDataLine阻塞RDP主循环）
    // =====================================================================

    private synchronized void enqueue(AudioDef format, byte[] data) {
        if (closed) {
            return;
        }
        AudioChunk chunk = new AudioChunk(format, data);
        if (!audioQueue.offer(chunk)) {
            audioQueue.poll(); // 队列满：丢弃最旧数据，防止延迟无限增大
            audioQueue.offer(chunk);
        }
    }

    private void playLoop() {
        while (!closed) {
            AudioChunk chunk;
            try {
                chunk = audioQueue.poll(PLAYBACK_IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (chunk == CLOSE_PLAYBACK) {
                closeLine();
            } else if (chunk != null) {
                try {
                    playChunk(chunk);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "rdpsnd: 播放失败: " + e.getMessage());
                }
            } else {
                // Windows并不保证媒体停止时立即发送SNDC_CLOSE。空闲时主动关闭
                // SourceDataLine，避免设备在欠载状态持续输出最后一个非零采样。
                closeLine();
            }
        }
        closeLine();
    }

    private void playChunk(AudioChunk chunk) {
        AudioDef f = chunk.format;
        if (line == null || lineFormat == null || !lineFormat.playbackEquals(f)) {
            reopenLine(f);
        }
        if (line != null) {
            line.write(chunk.data, 0, chunk.data.length);
        }
    }

    private void reopenLine(AudioDef f) {
        closeLine();
        try {
            // PCM 16bit为有符号小端；8bit为无符号
            AudioFormat af = new AudioFormat(f.samplesPerSec, f.bits, f.channels,
                    f.bits != 8, false);
            int bufferBytes = Math.max(f.blockAlign * 64,
                    f.avgBytesPerSec / 5); // 约200ms
            SourceDataLine l = AudioSystem.getSourceDataLine(af);
            l.open(af, bufferBytes);
            l.start();
            line = l;
            lineFormat = f;
            logger.info("rdpsnd: 音频输出已打开 " + f);
        } catch (LineUnavailableException e) {
            line = null;
            lineFormat = null;
            logger.log(Level.WARNING, "rdpsnd: 无法打开音频输出设备: " + e.getMessage());
        }
    }

    /** 仅播放线程调用 */
    private void closeLine() {
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
            line = null;
            lineFormat = null;
        }
    }

    /** 请求关闭播放管线；SourceDataLine仍只由播放线程访问。 */
    private synchronized void closePlayback() {
        audioQueue.clear();
        audioQueue.offer(CLOSE_PLAYBACK);
    }

    /**
     * 通道关闭（断开连接/重连前调用）：停止播放线程并释放音频设备。
     */
    public void shutdown() {
        closed = true;
        cancelFirstWaveRecovery();
        recoveryTimer.shutdownNow();
        audioQueue.clear();
        if (playThread != null) {
            playThread.interrupt();
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** 协商的音频格式（WAVEFORMATEX字段） */
    private static final class AudioDef {
        final int tag;
        final int channels;
        final int samplesPerSec;
        final int avgBytesPerSec;
        final int blockAlign;
        final int bits;

        AudioDef(int tag, int channels, int samplesPerSec, int avgBytesPerSec,
                int blockAlign, int bits) {
            this.tag = tag;
            this.channels = channels;
            this.samplesPerSec = samplesPerSec;
            this.avgBytesPerSec = avgBytesPerSec;
            this.blockAlign = blockAlign;
            this.bits = bits;
        }

        /** 播放参数是否一致（决定是否需要重开SourceDataLine） */
        boolean playbackEquals(AudioDef o) {
            return o != null && samplesPerSec == o.samplesPerSec
                    && channels == o.channels && bits == o.bits;
        }

        @Override
        public String toString() {
            return "PCM " + samplesPerSec + "Hz " + bits + "bit " + channels + "ch";
        }
    }

    /** 待播放音频块 */
    private static final class AudioChunk {
        final AudioDef format;
        final byte[] data;

        AudioChunk(AudioDef format, byte[] data) {
            this.format = format;
            this.data = data;
        }
    }
}
