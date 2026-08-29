package com.tangluobo.tomato.rdp.sound;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;

import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.rdp5.VChannel;

import static com.tangluobo.tomato.rdp.rdp5.VChannels.CHANNEL_OPTION_COMPRESS_RDP;
import static com.tangluobo.tomato.rdp.rdp5.VChannels.CHANNEL_OPTION_SHOW_PROTOCOL;

/**
 * RDP音频重定向虚拟通道（rdpsnd），基于MS-RDPEA规范实现。
 *
 * javardp库只实现了cliprdr通道，无音频支持——远程会话全程无声。
 * 本通道补齐音频输出（服务器→客户端方向）：
 * 1. 连接后服务器发送SNDC_FORMATS通告其支持的音频格式；
 *    客户端筛选出可播放的PCM格式并应答（服务器从中选定发送格式）；
 * 2. SNDC_TRAINING训练回显（服务器用于链路质量探测）；
 * 3. SNDC_WAVE/SNDC_WAVE2音频数据块 → 交{@link RdpAudioPlayer}播放；
 * 4. SNDC_WAVECONFIRM回执驱动服务器发送节奏；
 * 5. SNDC_SETVOLUME音量控制、SNDC_CLOSE结束通知。
 *
 * 协议细节参考FreeRDP rdpsnd_main.c：
 * - Wave Info(0x02)与Wave续传是两个连续的虚拟通道消息：音频前4字节
 *   随Wave Info下发，续传消息头4字节为填充，需替换回前4字节才是完整音频；
 * - Wave2(0x09，Win8+服务器)将全部数据放在单消息内，无续传。
 */
public class RdpSoundChannel extends VChannel {

    private static final Logger logger = Logger.getLogger(RdpSoundChannel.class.getName());

    // ===== MS-RDPEA 消息类型 =====
    private static final int SNDC_WAVE = 0x02;
    private static final int SNDC_WAVECONFIRM = 0x03;
    private static final int SNDC_CLOSE = 0x04;
    private static final int SNDC_SETVOLUME = 0x05;
    private static final int SNDC_TRAINING = 0x06;
    private static final int SNDC_FORMATS = 0x07;
    private static final int SNDC_WAVE2 = 0x09;
    private static final int SNDC_QUALITYMODE = 0x0C;

    /** WAVE_FORMAT_PCM */
    private static final int WAVE_FORMAT_PCM = 0x0001;

    /** 客户端能力标志：存活 + 支持音量 */
    private static final int TSSNDCAPS_ALIVE = 0x00000001;
    private static final int TSSNDCAPS_VOLUME = 0x00000002;

    /** 客户端协议版本（Win8+，FreeRDP同值） */
    private static final int CHANNEL_VERSION_WIN_MAX = 0x08;
    /** Win7版本阈值：服务器版本≥此值时需发送QualityMode */
    private static final int CHANNEL_VERSION_WIN_7 = 0x06;

    /** 通道选项：已初始化 | RDP加密（FreeRDP rdpsnd同值） */
    private static final int CHANNEL_OPTION_INITIALIZED = 0x80000000;
    private static final int CHANNEL_OPTION_ENCRYPT_RDP = 0x40000000;

    // ===== 状态 =====
    private final RdpAudioPlayer player = new RdpAudioPlayer();
    /** 服务器通告的格式 */
    private final List<AudioFormatEntry> serverFormats = new ArrayList<>();
    /** 我方应答的格式（wFormatNo索引到此列表） */
    private final List<AudioFormatEntry> clientFormats = new ArrayList<>();

    /** 等待Wave续传消息（前一条是Wave Info） */
    private boolean expectingWave;
    /** Wave Info携带的音频前4字节 */
    private final byte[] waveData = new byte[4];
    /** 完整音频块大小（Wave Info的BodySize-8） */
    private int waveDataSize;
    /** 当前块的wTimeStamp/cBlockNo（WaveConfirm回执用） */
    private int waveTimeStamp;
    private int waveBlockNo;
    /** 当前块使用的格式号（索引clientFormats，播放器按此开Line） */
    private int waveFormatNo;
    /** 服务器协议版本 */
    private int serverVersion;

    /**
     * 音频格式条目（WAVEFORMATEX），保留原始字节用于应答时原样回传
     */
    private static class AudioFormatEntry {
        int formatTag;
        int channels;
        long samplesPerSec;
        int bitsPerSample;
        int blockAlign;
        /** 原始WAVEFORMATEX字节（18+cbSize），应答时直接拷贝 */
        final byte[] raw;

        AudioFormatEntry(byte[] raw, int formatTag, int channels, long samplesPerSec,
                int bitsPerSample, int blockAlign) {
            this.raw = raw;
            this.formatTag = formatTag;
            this.channels = channels;
            this.samplesPerSec = samplesPerSec;
            this.bitsPerSample = bitsPerSample;
            this.blockAlign = blockAlign;
        }

        /** 转换为Java Sound格式（仅PCM） */
        AudioFormat toJavaFormat() {
            // WAV规范：8bit PCM无符号，>8bit有符号；RDP小端
            return new AudioFormat(samplesPerSec, bitsPerSample, channels,
                    bitsPerSample > 8, false);
        }
    }

    @Override
    public String name() {
        return "rdpsnd";
    }

    @Override
    public int flags() {
        // 通道options由Secure层用setBigEndian32(flags())写入MCS Connect Initial
        // 的CHANNEL_DEFINITION（见Secure.sendClientData：channel(i).flags() →
        // setBigEndian32），服务器按原样（大端）读回该值。
        //
        // 因此这里必须直接返回含CHANNEL_OPTION_INITIALIZED(0x80000000)位的值，
        // 让setBigEndian32写出"c0 a0 00 00"，服务器读回0xC0A00000才会判定
        // 通道已初始化并启动rdpsnd音频流。
        //
        // 曾错误地对0xC0000000做Integer.reverseBytes()，setBigEndian32写出
        // "00 00 00 c0"，服务器大端读回0x000000C0（丢失INITIALIZED位），
        // 认为通道未初始化，从而不发送任何音频数据（连SNDC_FORMATS都没有）。
        //
        // 与库内ClipChannel.flags()=0xC0A00000（INITIALIZED|ENCRYPT_RDP|
        // COMPRESS_RDP|SHOW_PROTOCOL）保持一致，已验证能被Windows服务器接受。
        return CHANNEL_OPTION_INITIALIZED | CHANNEL_OPTION_ENCRYPT_RDP
                | CHANNEL_OPTION_COMPRESS_RDP | CHANNEL_OPTION_SHOW_PROTOCOL;
    }

    @Override
    public void process(Packet packet) throws RdesktopException, IOException {
        try {
            // Wave续传消息：无协议头，整包是音频数据（前4字节为填充）
            if (expectingWave) {
                recvWaveContinuation(packet);
                return;
            }

            int msgType = packet.get8();
            packet.get8(); // bPad
            int bodySize = packet.getLittleEndian16();

            switch (msgType) {
                case SNDC_FORMATS:
                    recvServerFormats(packet);
                    break;
                case SNDC_TRAINING:
                    recvTraining(packet);
                    break;
                case SNDC_WAVE:
                    recvWaveInfo(packet, bodySize);
                    break;
                case SNDC_WAVE2:
                    recvWave2(packet, bodySize);
                    break;
                case SNDC_SETVOLUME:
                    recvVolume(packet);
                    break;
                case SNDC_CLOSE:
                    logger.info("服务器关闭音频重定向");
                    break;
                default:
                    logger.fine("rdpsnd忽略消息: msgType=0x" + Integer.toHexString(msgType)
                            + " bodySize=" + bodySize);
                    break;
            }
        } catch (Exception e) {
            // 音频异常不应断开会话：记录并继续
            logger.log(Level.WARNING, "rdpsnd消息处理异常: " + e.getMessage(), e);
        }
    }

    /**
     * 停止音频播放（断开连接时调用）
     */
    public void close() {
        player.stop();
    }

    // =====================================================================
    // SNDC_FORMATS：格式协商
    // =====================================================================

    /**
     * 解析服务器格式列表，筛选可播放的PCM格式并应答。
     */
    private void recvServerFormats(Packet packet) throws RdesktopException, IOException {
        if (packet.getEnd() - packet.getPosition() < 20) {
            logger.warning("SNDC_FORMATS消息体过短");
            return;
        }
        packet.getLittleEndian32(); // dwFlags
        packet.getLittleEndian32(); // dwVolume
        packet.getLittleEndian32(); // dwPitch
        packet.getLittleEndian16(); // wDGramPort
        int numFormats = packet.getLittleEndian16();
        packet.get8();              // cLastBlockConfirmed
        serverVersion = packet.getLittleEndian16();
        packet.get8();              // bPad

        serverFormats.clear();
        clientFormats.clear();

        for (int i = 0; i < numFormats; i++) {
            AudioFormatEntry entry = readAudioFormat(packet);
            if (entry == null) {
                logger.warning("音频格式解析失败（第" + i + "项），停止解析剩余格式");
                break;
            }
            serverFormats.add(entry);
        }

        // 筛选：仅接受可由Java Sound直接播放的PCM格式
        for (AudioFormatEntry entry : serverFormats) {
            if (isPlayable(entry)) {
                clientFormats.add(entry);
            }
        }

        logger.info("服务器音频格式: " + serverFormats.size() + "个，可播放: " + clientFormats.size()
                + "个 (服务器版本=" + serverVersion + ")");

        sendClientFormats();
        if (serverVersion >= CHANNEL_VERSION_WIN_7) {
            sendQualityMode();
        }
    }

    /** PCM可播放条件：非零采样率/声道/位深/块对齐，位深8/16/24 */
    private boolean isPlayable(AudioFormatEntry e) {
        if (e.formatTag != WAVE_FORMAT_PCM) {
            return false;
        }
        return e.samplesPerSec > 0 && e.samplesPerSec <= 192000
                && e.channels >= 1 && e.channels <= 2
                && (e.bitsPerSample == 8 || e.bitsPerSample == 16 || e.bitsPerSample == 24)
                && e.blockAlign > 0;
    }

    /**
     * 读取一个WAVEFORMATEX格式（18+cbSize字节），同时保留原始字节。
     */
    private AudioFormatEntry readAudioFormat(Packet packet) {
        int start = packet.getPosition();
        if (packet.getEnd() - start < 18) {
            return null;
        }
        int formatTag = packet.getLittleEndian16();
        int channels = packet.getLittleEndian16();
        long samplesPerSec = packet.getLittleEndian32() & 0xFFFFFFFFL;
        packet.getLittleEndian32(); // nAvgBytesPerSec
        int blockAlign = packet.getLittleEndian16();
        int bitsPerSample = packet.getLittleEndian16();
        int cbSize = packet.getLittleEndian16();
        if (packet.getEnd() - packet.getPosition() < cbSize || cbSize < 0) {
            return null;
        }
        packet.incrementPosition(cbSize);

        int total = packet.getPosition() - start;
        byte[] raw = new byte[total];
        packet.copyToByteArray(raw, 0, start, total);
        return new AudioFormatEntry(raw, formatTag, channels, samplesPerSec,
                bitsPerSample, blockAlign);
    }

    /**
     * 应答客户端格式列表（MS-RDPEA 2.2.2.2）。
     */
    private void sendClientFormats() throws RdesktopException, IOException {
        int bodySize = 20 + 18 * clientFormats.size();
        for (AudioFormatEntry e : clientFormats) {
            bodySize += e.raw.length - 18;
        }

        Packet p = new Packet(4 + bodySize);
        p.set8(SNDC_FORMATS);
        p.set8(0); // bPad
        p.setLittleEndian16(bodySize);
        p.setLittleEndian32(TSSNDCAPS_ALIVE | TSSNDCAPS_VOLUME); // dwFlags
        p.setLittleEndian32(0xFFFFFFFF); // dwVolume（最大音量）
        p.setLittleEndian32(0);          // dwPitch
        p.setLittleEndian16(0);          // wDGramPort（仅UDP模式使用）
        p.setLittleEndian16(clientFormats.size());
        p.set8(0);                        // cLastBlockConfirmed
        p.setLittleEndian16(CHANNEL_VERSION_WIN_MAX); // wVersion
        p.set8(0);                        // bPad
        for (AudioFormatEntry e : clientFormats) {
            p.copyFromByteArray(e.raw, 0, p.getPosition(), e.raw.length);
            p.incrementPosition(e.raw.length);
        }
        p.markEnd();
        sendWithLock(p);

        if (clientFormats.isEmpty()) {
            logger.warning("无可播放的音频格式，远程音频将被禁用");
        } else {
            AudioFormatEntry first = clientFormats.get(0);
            logger.info(String.format("已应答音频格式: %d个，首个: PCM %dHz %dbit %d声道",
                    clientFormats.size(), first.samplesPerSec, first.bitsPerSample, first.channels));
            player.start();
        }
    }

    /**
     * 发送QualityMode（服务器版本≥Win7时必需，影响服务器端编码质量）。
     */
    private void sendQualityMode() throws RdesktopException, IOException {
        Packet p = new Packet(4 + 4);
        p.set8(SNDC_QUALITYMODE);
        p.set8(0);
        p.setLittleEndian16(4);
        p.setLittleEndian16(2); // HIGH_QUALITY
        p.setLittleEndian16(0); // Reserved
        p.markEnd();
        sendWithLock(p);
    }

    // =====================================================================
    // SNDC_TRAINING：训练回显
    // =====================================================================

    private void recvTraining(Packet packet) throws RdesktopException, IOException {
        if (packet.getEnd() - packet.getPosition() < 4) {
            return;
        }
        int timeStamp = packet.getLittleEndian16();
        int packSize = packet.getLittleEndian16();

        Packet p = new Packet(4 + 4);
        p.set8(SNDC_TRAINING);
        p.set8(0);
        p.setLittleEndian16(4);
        p.setLittleEndian16(timeStamp);
        p.setLittleEndian16(packSize);
        p.markEnd();
        sendWithLock(p);
        logger.fine("已回显训练消息: wTimeStamp=" + timeStamp + " wPackSize=" + packSize);
    }

    // =====================================================================
    // SNDC_WAVE / SNDC_WAVE2：音频数据
    // =====================================================================

    /**
     * Wave Info：携带音频块元信息+前4字节数据，数据主体在下一条消息。
     */
    private void recvWaveInfo(Packet packet, int bodySize) {
        if (packet.getEnd() - packet.getPosition() < 12) {
            logger.warning("SNDC_WAVE消息体过短");
            return;
        }
        waveTimeStamp = packet.getLittleEndian16();
        waveFormatNo = packet.getLittleEndian16();
        waveBlockNo = packet.get8();
        packet.incrementPosition(3); // bPad
        packet.copyToByteArray(waveData, 0, packet.getPosition(), 4);

        // 完整音频大小 = BodySize - 8（8=TimeStamp+FormatNo+BlockNo+Pad）
        waveDataSize = bodySize - 8;
        if (waveDataSize < 4 || waveFormatNo >= clientFormats.size()) {
            logger.warning("无效Wave Info: formatNo=" + waveFormatNo + " waveDataSize=" + waveDataSize);
            return;
        }
        expectingWave = true;
    }

    /**
     * Wave续传消息：前4字节为填充，替换为Wave Info携带的前4字节后
     * 得到完整音频块，立即播放并回执WaveConfirm。
     */
    private void recvWaveContinuation(Packet packet) {
        expectingWave = false;
        int avail = packet.getEnd() - packet.getPosition();
        if (avail < 4 || waveDataSize < 4) {
            return;
        }
        int total = Math.min(waveDataSize, avail);
        byte[] audio = new byte[total];
        // 前4字节来自Wave Info
        audio[0] = waveData[0];
        audio[1] = waveData[1];
        audio[2] = waveData[2];
        audio[3] = waveData[3];
        // 其余来自续传消息（跳过填充头）
        int copyLen = Math.min(avail - 4, total - 4);
        if (copyLen > 0) {
            packet.copyToByteArray(audio, 4, packet.getPosition() + 4, copyLen);
        }

        playCurrentBlock(audio);
        sendWaveConfirm(waveTimeStamp);
    }

    /**
     * Wave2（Win8+服务器）：元信息+全部音频数据在单消息内。
     */
    private void recvWave2(Packet packet, int bodySize) {
        if (packet.getEnd() - packet.getPosition() < 12) {
            return;
        }
        waveTimeStamp = packet.getLittleEndian16();
        waveFormatNo = packet.getLittleEndian16();
        waveBlockNo = packet.get8();
        packet.incrementPosition(3); // bPad
        packet.getLittleEndian32(); // dwAudioTimeStamp

        if (waveFormatNo >= clientFormats.size()) {
            logger.warning("无效Wave2: formatNo=" + waveFormatNo);
            return;
        }
        int total = bodySize - 12;
        if (total <= 0) {
            return;
        }
        byte[] audio = new byte[total];
        packet.copyToByteArray(audio, 0, packet.getPosition(), total);

        playCurrentBlock(audio);
        sendWaveConfirm(waveTimeStamp);
    }

    /** 提交当前块到播放器（使用waveFormatNo索引clientFormats） */
    private void playCurrentBlock(byte[] audio) {
        if (waveFormatNo < 0 || waveFormatNo >= clientFormats.size()) {
            return;
        }
        AudioFormatEntry entry = clientFormats.get(waveFormatNo);
        player.submit(waveFormatNo, entry.toJavaFormat(), audio);
    }

    /**
     * 回执WaveConfirm（服务器据此控制发送节奏）。
     */
    private void sendWaveConfirm(int timeStamp) {
        try {
            Packet p = new Packet(4 + 4);
            p.set8(SNDC_WAVECONFIRM);
            p.set8(0);
            p.setLittleEndian16(4);
            p.setLittleEndian16(timeStamp);
            p.set8(waveBlockNo);
            p.set8(0);
            p.markEnd();
            sendWithLock(p);
        } catch (Exception e) {
            logger.log(Level.FINE, "发送WaveConfirm失败: " + e.getMessage());
        }
    }

    // =====================================================================
    // SNDC_SETVOLUME：音量
    // =====================================================================

    private void recvVolume(Packet packet) {
        if (packet.getEnd() - packet.getPosition() < 4) {
            return;
        }
        int volume = packet.getLittleEndian32();
        player.setVolume(volume & 0xFFFF);
    }

    // =====================================================================
    // 发送辅助
    // =====================================================================

    /**
     * 持CommLock发送（与ClipChannel.send_data一致：跨线程发送串行化，
     * 避免与AWT输入事件并发写socket）。
     */
    private void sendWithLock(Packet packet) throws RdesktopException, IOException {
        try {
            state.getCommLock().acquire();
        } catch (InterruptedException e) {
            throw new RdesktopException("Interrupted waiting to send audio data.", e);
        }
        try {
            send_packet(packet);
        } finally {
            state.getCommLock().release();
        }
    }
}
