package com.tangluobo.tomato.rdp;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.IContext;
import com.tangluobo.tomato.rdp.OrderException;
import com.tangluobo.tomato.rdp.RdesktopDisconnectException;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.layers.Rdp;
import com.tangluobo.tomato.rdp.rdp5.VChannels;

/**
 * 修复版RDP层，覆盖关键方法添加修复和诊断日志。
 */
public class RdpPatch extends Rdp {

    private static final Logger logger = Logger.getLogger(RdpPatch.class.getName());

    private final State stateRef;
    private final AtomicInteger bitmapUpdateCount = new AtomicInteger(0);
    private final AtomicInteger rdp5PacketCount = new AtomicInteger(0);
    private final AtomicInteger totalPduCount = new AtomicInteger(0);
    private volatile long lastReceiveEnterTime = 0;
    private volatile int lastReasonSeen = 0;
    private volatile int lastServerStatusSeen = 0;
    private volatile Consumer<Void> onFirstFrame;
    private volatile boolean firstFrameReceived = false;
    private final java.util.List<String> pduHistory = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // 反射访问Rdp的private方法/字段
    private final Method receiveMethod;
    private final Method processPacketMethod;
    private final Field streamField;
    private final Field nextPacketField;

    public RdpPatch(IContext context, State state, VChannels channels) {
        super(context, state, channels);
        this.stateRef = state;

        // 反射获取private方法和字段
        Method rm = null, pm = null;
        Field sf = null, npf = null;
        try {
            rm = Rdp.class.getDeclaredMethod("receive", int[].class);
            rm.setAccessible(true);
            pm = Rdp.class.getDeclaredMethod("processPacket", int[].class, com.tangluobo.tomato.rdp.Packet.class);
            pm.setAccessible(true);
            sf = Rdp.class.getDeclaredField("stream");
            sf.setAccessible(true);
            npf = Rdp.class.getDeclaredField("next_packet");
            npf.setAccessible(true);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "反射获取Rdp私有方法/字段失败: " + e.getMessage(), e);
        }
        receiveMethod = rm;
        processPacketMethod = pm;
        streamField = sf;
        nextPacketField = npf;
    }

    @Override
    public void rdp5_process(com.tangluobo.tomato.rdp.Packet s, boolean encryption, boolean shortform)
            throws RdesktopException, OrderException {
        int pktNum = rdp5PacketCount.incrementAndGet();
        boolean isSSL = stateRef.getSecurityType().isSSL();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(String.format("[RDP5 #%d] encryption=%b, shortform=%b, securityType=%s, dataSize=%d",
                    pktNum, encryption, shortform, stateRef.getSecurityType(), s.getEnd() - s.getPosition()));
        }

        if (encryption && isSSL) {
            logger.finest("[RDP5] Ignoring legacy encryption flag for TLS security");
            encryption = false;
        }

        int length, count;
        int type;
        int next;

        if (encryption) {
            // FASTPATH_OUTPUT_ENCRYPTED carries an 8-byte dataSignature before the
            // encrypted updates (MS-RDPBCGR 2.2.9.1.2). The previous 6/7-byte skip
            // started decryption mid-signature and corrupted every bitmap update.
            s.incrementPosition(8);
            byte[] data = new byte[s.size() - s.getPosition()];
            s.copyToByteArray(data, 0, s.getPosition(), data.length);
            byte[] packet = secureLayer.decrypt(data);
            if (packet != null) {
                s.copyFromByteArray(packet, 0, s.getPosition(), packet.length);
            }
        }

        while (s.getPosition() < s.getEnd()) {
            // 修复：正确解析 updateHeader 位域 (MS-RDPBCGR 2.2.9.1.2.1)
            // updateHeader (MS-RDPBCGR 2.2.9.1.2.1):
            //   updateCode     = bits 0..3
            //   fragmentation  = bits 4..5
            //   compression    = bits 6..7
            // 例如本次服务器发送的 0x01 表示 Bitmap update。此前把位域顺序
            // 反了，错误地按 Orders 解析每一帧，导致所有桌面数据被丢弃。
            int updateHeader = s.get8();
            int updateCode = updateHeader & 0x0F;
            int fragmentation = (updateHeader >> 4) & 0x03;
            int compression = (updateHeader >> 6) & 0x03;

            // 仅 FASTPATH_OUTPUT_COMPRESSION_USED (0x02) 表示 compressionFlags 存在。
            // bit 0 只是保留位，不能据此读取额外字节。
            int compressionFlags = 0;
            if (compression == 0x02) {
                compressionFlags = s.get8();
            }

            length = s.getLittleEndian16();
            next = s.getPosition() + length;
            type = updateCode; // 用 updateCode 作为 switch 类型

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(String.format("[RDP5 #%d] updateHeader=0x%02x, updateCode=%d, frag=%d, comp=%d(compFlags=0x%02x), length=%d",
                        pktNum, updateHeader, updateCode, fragmentation, compression, compressionFlags, length));
            }

            // 诊断：分片 bitmap update 警告（单片处理可能解析失败）
            if (type == 1 && fragmentation != 0) {
                logger.warning(String.format("[RDP5 #%d] bitmap update 分片未合并: frag=%d (0=SINGLE,1=LAST,2=FIRST,3=NEXT)", pktNum, fragmentation));
            }

            switch (type) {
            case 0: // orders
                count = s.getLittleEndian16();
                orders.processOrders(s, next, count);
                break;
            case 1: // bitmap update
                s.incrementPosition(2);
                processBitmapUpdates(s);
                break;
            case 2: // palette
                s.incrementPosition(2);
                processPalette(s);
                break;
            case 3: break;
            case 5: process_null_system_pointer_pdu(s); break;
            case 6: break;
            case 8: break; // pointer position; local Swing cursor position is authoritative
            case 9: process_colour_pointer_pdu(s); break;
            case 10: process_cached_pointer_pdu(s); break;
            case 11: process_colour_pointer_pdu_new(s); break;
            default:
                logger.warning("Unimplemented RDP5 updateCode " + type
                        + " (updateHeader=0x" + String.format("%02x", updateHeader) + ")");
            }
            s.setPosition(next);
        }
    }

    @Override
    public void rdp5_process(com.tangluobo.tomato.rdp.Packet s, boolean e)
            throws RdesktopException, OrderException {
        rdp5_process(s, e, false);
    }

    @Override
    protected void processBitmapUpdates(com.tangluobo.tomato.rdp.Packet data) throws RdesktopException {
        int count = bitmapUpdateCount.incrementAndGet();
        int pos = data.getPosition();
        int n_updates = data.getLittleEndian16();
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(String.format("[BITMAP UPDATE #%d] n_updates=%d", count, n_updates));
        }
        data.setPosition(pos);
        super.processBitmapUpdates(data);

        if (!firstFrameReceived) {
            firstFrameReceived = true;
            Consumer<Void> callback = onFirstFrame;
            if (callback != null) {
                callback.accept(null);
            }
        }

        if (logger.isLoggable(Level.FINEST)) try {
            java.awt.image.BufferedImage bi = stateRef.getCanvas().getDisplay().getBufferedImage();
            if (bi != null) {
                int w = bi.getWidth(), h = bi.getHeight();
                int[] xs = {0, w / 2, w - 1};
                int[] ys = {0, h / 2, h - 1};
                StringBuilder sb = new StringBuilder("[BITMAP #" + count + "] pixels(" + w + "x" + h + "):");
                for (int x : xs) for (int y : ys) {
                    sb.append(String.format(" (%d,%d)=%06x", x, y, bi.getRGB(x, y) & 0xFFFFFF));
                }
                logger.finest(sb.toString());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "采样BufferedImage失败: " + e.getMessage());
        }
    }

    @Override
    public void connect(com.tangluobo.tomato.rdp.io.IO io, com.tangluobo.tomato.rdp.CredentialProvider credentialProvider,
            String command, String directory) throws IOException, RdesktopException {
        logger.info("[CONNECT] Starting, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", bpp=" + stateRef.getServerBpp()
                + ", size=" + stateRef.getWidth() + "x" + stateRef.getHeight());
        super.connect(io, credentialProvider, command, directory);
        logger.info("[CONNECT] Done, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", licenceIssued=" + stateRef.isLicenceIssued()
                + ", serverBpp=" + stateRef.getServerBpp());
    }

    @Override
    public void mainLoop() throws IOException, RdesktopException {
        logger.info("[MAINLOOP] Entering main loop, rdp5=" + stateRef.isRDP5());

        if (receiveMethod == null || processPacketMethod == null) {
            logger.warning("[MAINLOOP] 反射方法不可用，使用父类mainLoop");
            super.mainLoop();
            return;
        }

        // 看门狗线程：每10秒检查mainLoop是否卡在receive()
        Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10000);
                    long stuckMs = System.currentTimeMillis() - lastReceiveEnterTime;
                    if (stuckMs > 10000 && lastReceiveEnterTime > 0) {
                        logger.warning(String.format(
                            "[WATCHDOG] mainLoop已卡在receive() %.1f秒, totalPDUs=%d, bitmaps=%d, rdp5=%d, sent=%d, recv=%d, bcInAvailable=%d, active=%b, licenceIssued=%b, lastReason=0x%x, isoInjected=%b, isoRecvCalled=%b, isoError=%s, PDU历史=%s",
                            stuckMs / 1000.0, totalPduCount.get(), bitmapUpdateCount.get(),
                            rdp5PacketCount.get(), RdpTlsFix.RdpTransport.getSendPktCount(),
                            RdpTlsFix.RdpTransport.getRecvPktCount(),
                            RdpTlsFix.RdpTransport.getBcInAvailable(),
                            stateRef.isActive(), stateRef.isLicenceIssued(),
                            stateRef.getLastReason(),
                            RdpIsoFix.isInjected(), RdpIsoFix.isReceiveCalled(),
                            RdpIsoFix.getInjectError(),
                            String.join(",", pduHistory)));
                    }
                }
            } catch (InterruptedException e) { /* 正常退出 */ }
        }, "RDP-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
        int[] type = new int[1];
        com.tangluobo.tomato.rdp.Packet data;
        while (true) {
            // 调用private receive()
            data = null;
            lastReceiveEnterTime = System.currentTimeMillis();
            try {
                data = (com.tangluobo.tomato.rdp.Packet) receiveMethod.invoke(this, (Object) type);
                if (data == null) {
                    logger.info("[MAINLOOP] receive() returned null, exiting");
                    return;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof EOFException) {
                    logger.info("[MAINLOOP] EOF, exiting");
                    if (stateRef.getLastReason() > 0) {
                        throw new RdesktopDisconnectException(stateRef.getLastReason());
                    }
                    return;
                }
                if (cause instanceof IOException) {
                    logger.log(Level.SEVERE, "[MAINLOOP] IO error after " + totalPduCount.get() + " PDUs: " + cause.getMessage());
                    if (stateRef.getLastReason() > 0)
                        throw new RdesktopDisconnectException(stateRef.getLastReason());
                    else
                        throw new RdesktopDisconnectException(0, (IOException) cause);
                }
                if (cause instanceof RdesktopException) throw (RdesktopException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("receive failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective receive failed: " + e.getMessage(), e);
            }

            int pduCount = totalPduCount.incrementAndGet();
            int pduType = type[0];

            // 诊断：输出PDU数据和stream状态
            String pduName;
            switch (pduType) {
            case 1: pduName = "DEMAND_ACTIVE"; break;
            case 6: pduName = "DEACTIVATE_ALL"; break;
            case 7: pduName = "DATA"; break;
            case 10: pduName = "SERVER_REDIRECTION"; break;
            case 0: pduName = "KEEPALIVE"; break;
            default: pduName = "UNKNOWN(" + pduType + ")"; break;
            }
            int dataAvail = data.getEnd() - data.getPosition();
            // 对DATA PDU(type=7)，解析子类型(shareDataHeader中的dataType)
            String dataSubType = "";
            if (pduType == 7 && dataAvail >= 9) {
                int savePos = data.getPosition();
                data.incrementPosition(6); // skip shareid(4)+pad(1)+streamid(1)
                data.getLittleEndian16(); // len
                int dataType = data.get8();
                data.setPosition(savePos);
                dataSubType = " subType=" + dataType;
                switch (dataType) {
                    case 0: dataSubType += "(UPDATE)"; break;
                    case 2: dataSubType += "(UPDATE_BITMAP)"; break; 
                    case 3: dataSubType += "(PALETTE)"; break;
                    case 20: dataSubType += "(CONTROL)"; break;
                    case 27: dataSubType += "(POINTER)"; break;
                    case 31: dataSubType += "(SYNCHRONISE)"; break;
                    case 33: dataSubType += "(REFRESH_RECT)"; break;
                    case 34: dataSubType += "(PLAY_SOUND)"; break;
                    case 36: dataSubType += "(SUPPRESS_OUTPUT)"; break;
                    case 37: dataSubType += "(SAVE_SESSION_INFO)"; break;
                    case 38: dataSubType += "(FONTLIST)"; break;
                    case 39: dataSubType += "(FONTMAP)"; break;
                    case 40: dataSubType += "(SET_KEYBOARD_INDICATORS)"; break;
                    case 47: dataSubType += "(SET_ERROR_INFO)"; break;
                    default: break;
                }
            }
            pduHistory.add(String.format("#%d:%s%s", pduCount, pduName, dataSubType.isEmpty() ? "" : dataSubType.split("=")[1].replace(")", "").replace("(", "")));
            logger.info(String.format("[MAINLOOP] PDU #%d: type=%s(%d)%s, dataSize=%d",
                    pduCount, pduName, pduType, dataSubType, dataAvail));

            // DEMAND_ACTIVE处理后，记录关键状态
            if (pduType == 1) {
                logger.info(String.format("[CAPS] serverBpp=%d, width=%d, height=%d, rdp5=%b, serverChannelId=%d, shareId=%d",
                        stateRef.getServerBpp(), stateRef.getWidth(), stateRef.getHeight(),
                        stateRef.isRDP5(), stateRef.getServerChannelId(), stateRef.getShareId()));
            }

            // 诊断：输出data的前16字节hex
            if (dataAvail > 0) {
                int savePos = data.getPosition();
                int dumpLen = Math.min(dataAvail, 32);
                StringBuilder hexSb = new StringBuilder("[MAINLOOP] PDU #" + pduCount + " hex:");
                for (int i = 0; i < dumpLen; i++) {
                    hexSb.append(String.format(" %02x", data.get8()));
                }
                data.setPosition(savePos);
                logger.info(hexSb.toString());
            }

            // 调用private processPacket()
            // DEMAND_ACTIVE(type=1)会触发processDemandActive，内部接收4个PDU(SYNCHRONIZE/COOPERATE/GRANT_CONTROL/FONT_MAP)
            // 如果服务器不发送这些PDU，processPacket会阻塞在这里
            long processStart = System.currentTimeMillis();
            try {
                processPacketMethod.invoke(this, (Object) type, data);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RdesktopException) {
                    logger.log(Level.SEVERE, String.format("[MAINLOOP] processPacket error at PDU #%d: %s", pduCount, cause.getMessage()));
                    throw (RdesktopException) cause;
                }
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof OrderException) throw new RdesktopException(cause.getMessage(), cause);
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("processPacket failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective processPacket failed: " + e.getMessage(), e);
            }
            long processMs = System.currentTimeMillis() - processStart;
            if (processMs > 100) {
                logger.info(String.format("[MAINLOOP] PDU #%d (%s) processPacket耗时 %dms", pduCount, pduName, processMs));
            }

            // 检测服务器是否发送了错误PDU (RDP_DATA_PDU_SET_ERROR)
            int lastReason = stateRef.getLastReason();
            if (lastReason != 0 && lastReason != lastReasonSeen) {
                lastReasonSeen = lastReason;
                logger.warning("[MAINLOOP] 服务器发送错误PDU! lastReason=0x" + Integer.toHexString(lastReason)
                        + " (" + lastReason + ")");
                // SET_ERROR_INFO is terminal. Some GNOME/xrdp servers leave the
                // socket open after sending it; waiting for another read leaks a
                // receive loop and watchdog and delays the real disconnect reason.
                throw new RdesktopDisconnectException(lastReason);
            }
            // 记录服务器状态
            int serverStatus = stateRef.getServerStatus();
            if (serverStatus != 0 && serverStatus != lastServerStatusSeen) {
                lastServerStatusSeen = serverStatus;
                logger.info("[MAINLOOP] 服务器状态变更: serverStatus=0x" + Integer.toHexString(serverStatus));
            }

            // 诊断：processPacket后stream状态
            try {
                if (streamField != null && nextPacketField != null) {
                    Object stream = streamField.get(this);
                    int nextPkt = nextPacketField.getInt(this);
                    if (stream != null) {
                        com.tangluobo.tomato.rdp.Packet p = (com.tangluobo.tomato.rdp.Packet) stream;
                        logger.info(String.format("[MAINLOOP] After PDU #%d: stream pos=%d end=%d, next_packet=%d, remaining=%d",
                                pduCount, p.getPosition(), p.getEnd(), nextPkt, p.getEnd() - nextPkt));
                    }
                }
            } catch (Exception e) {
                // 忽略诊断错误
            }

            // DEMAND_ACTIVE处理完毕后，processDemandActive内部已经发送了：
            // sendConfirmActive（含capabilities + ready(INPUT) → doLockKeys同步键状态）
            // sendSynchronize、sendControl、sendFonts，并接收了4个响应PDU。
            // doLockKeys已发送CapsLock/NumLock/ScrollLock同步事件，服务器应开始推送画面。
            // 注意：不再额外发送sendInput(0,0,0,0,0)——RDP_INPUT_SYNCHRONIZE在库中定义为0，
            // 但MS-RDPBCGR规范中INPUT_EVENT_SYNC=3，值0会被服务器当作未知事件忽略。
        }
        } finally {
            // A redirected/closed protocol instance must become read-only before
            // its old Swing canvas can dispatch another queued input event.
            markConnectionClosed();
            lastReceiveEnterTime = 0;
            watchdog.interrupt();
        }
    }

    public int getBitmapUpdateCount() { return bitmapUpdateCount.get(); }
    public int getRdp5PacketCount() { return rdp5PacketCount.get(); }
    public int getTotalPduCount() { return totalPduCount.get(); }

    /** Invoked after the first bitmap update has been decoded into the canvas. */
    public void setOnFirstFrame(Consumer<Void> callback) {
        this.onFirstFrame = callback;
    }
}
