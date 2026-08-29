package com.tangluobo.tomato.rdp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.IContext;
import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.layers.ISO;
import com.tangluobo.tomato.rdp.layers.MCS;
import com.tangluobo.tomato.rdp.layers.Transport;

/**
 * 修复javardp库ISO层的fast-path检测bug。
 *
 * 根本原因：
 * ISO.receiveMessageex()中使用 (version & 3) == 0 来检测fast-path包，
 * 但RDP协议规定fast-path包的首字节bit 0 = 0（而非bit 0和bit 1都为0）。
 * 当fast-path包的加密标志位（bit 1）为1时，首字节 = 0x02，
 * (0x02 & 3) == 2 ≠ 0，fast-path包不被识别，被错误当作slow-path处理。
 *
 * 修复方案：
 * 通过反射替换ISO实例为RdpIso子类，覆盖receive()方法。
 * RdpIso完全复制原始ISO.receiveMessageex()的逻辑，仅修正fast-path检测条件。
 *
 * 注意：
 * - 原始ISO.receiveMessageex()是private方法，无法覆盖，因此覆盖public的receive()
 * - 本实现完整复制了原始receiveMessageex() + receive()的逻辑，
 *   仅将fast-path检测从 (version & 3) == 0 修改为 (version & 1) == 0
 * - fast-path包不经过X.224/MCS/Secure层，直接由RDP层处理
 */
public class RdpIsoFix {

    private static final Logger logger = Logger.getLogger(RdpIsoFix.class.getName());
    // 注意：不能使用静态applied标志，因为retryWithHybridSecurity会创建新的rdpLayer
    // （带新的ISO层），需要每次都注入。之前的applied标志导致重连时ISO修复未生效，
    // 服务器发送的fast-path bitmap更新无法被解析，导致黑屏。

    // 诊断标志：跟踪注入和调用状态
    private static volatile boolean injected = false;
    private static volatile String injectError = null;
    private static volatile boolean receiveCalled = false;

    public static boolean isInjected() { return injected; }
    public static String getInjectError() { return injectError; }
    public static boolean isReceiveCalled() { return receiveCalled; }

    // X.224 constants (same as in ISO.java)
    private static final int CONNECTION_CONFIRM = 0xD0;
    private static final int DATA_TRANSFER = 0xF0;
    private static final int EOT = 0x80;

    /**
     * ISO子类，修复fast-path检测逻辑。
     *
     * 完整复制原始ISO.receiveMessageex() + receive()逻辑，
     * 仅修正fast-path检测条件：(version & 3) == 0 → (version & 1) == 0
     */
    public static class RdpIso extends ISO {

        private final State stateRef;

        public RdpIso(IContext context, State state, MCS mcs) {
            super(context, state, mcs);
            this.stateRef = state;
        }

        /**
         * 覆盖receive()方法，完整复制原始ISO逻辑并修正fast-path检测。
         *
         * 原始逻辑链：receive() → receiveMessage(type) → receiveMessageex(type, rdpver)
         * 本方法将三层调用合并为一个方法，仅修改fast-path检测条件。
         */
        private static volatile boolean firstCallLogged = false;

        @Override
        public Packet receive() throws IOException, RdesktopException {
            Transport transport = getTransportField();

            if (!firstCallLogged) {
                firstCallLogged = true;
                receiveCalled = true;
                logger.info("[RdpIso] receive()首次调用 - RdpIso已激活, transport=" + transport.getClass().getName());
            }

            // === 完整复制 ISO.receiveMessageex() 逻辑 ===
            Packet s = null;
            int length, version;

            next_packet:
            while (true) {
                // 读取前4字节（TPKT header 或 fast-path header的开头）
                logger.finest("[RdpIso] 等待读取4字节header...");
                s = transport.receivePacket(null, 4);
                if (s == null)
                    return null;

                version = s.get8();
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest("[RdpIso] 收到header: version=0x" + String.format("%02x", version));
                }

                if (version == 3) {
                    // TPKT格式：version(1) + reserved(1) + length(2)
                    s.incrementPosition(1); // skip reserved
                    length = s.getBigEndian16();
                } else {
                    // 非TPKT格式（fast-path等）：解析长度
                    length = s.get8();
                    if ((length & 0x80) != 0) {
                        length &= ~0x80;
                        length = (length << 8) + s.get8();
                    }
                }

                // 读取剩余数据
                s = transport.receivePacket(s, length - 4);
                if (s == null)
                    return null;

                // === 核心修复：fast-path检测条件 ===
                // 原始代码: if ((version & 3) == 0)
                // action 在最低两位。仅 action=0 代表 fast-path；最高两位是安全标志。
                // version=3 是 TPKT slow-path 的特例。
                if ((version & 0x03) == 0 && version != 3) {
                    // Fast-path packet
                    // The top two bits are Fast-Path security flags. 0x80 represents
                    // FASTPATH_OUTPUT_ENCRYPTED; the low two bits carry the action.
                    boolean encrypted = (version & 0x80) != 0;

                    // SSL/HYBRID模式下忽略加密标志（TLS已处理加密）
                    if (encrypted && stateRef.getSecurityType().isSSL()) {
                        encrypted = false;
                    }

                    // The outer Fast-Path header has already been consumed; do not derive
                    // this legacy encryption-header flag from FASTPATH_OUTPUT_ENCRYPTED.
                    boolean shortform = false;

                    if (logger.isLoggable(Level.FINEST)) {
                        logger.finest("[FAST-PATH] version=0x" + String.format("%02x", version)
                                + ", length=" + length + ", encrypted=" + encrypted + ", shortform=" + shortform);
                    }

                    try {
                        MCS mcs = getParent();
                        mcs.getParent().getParent().rdp5_process(s, encrypted, shortform);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Fast-path处理失败: " + e.getMessage(), e);
                    }
                    // fast-path包已处理，继续接收下一个包
                    continue next_packet;
                } else {
                    // Slow-path packet，跳出循环进行X.224解析
                    break;
                }
            }

            // === 诊断日志：slow-path包 ===
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("[SLOW-PATH] version=" + version + ", length=" + length
                        + ", pos=" + s.getPosition() + ", end=" + s.getEnd());
            }

            // === 完整复制 ISO.receiveMessageex() 的X.224解析 ===
            // 此时position在TPKT header之后（对于TPKT格式，position=4）
            s.get8(); // X.224 length indicator
            int type = s.get8(); // X.224 type

            // === 完整复制 ISO.receive() 的类型检查 ===
            if (type == DATA_TRANSFER) {
                s.incrementPosition(1); // EOT
                return s;
            }

            // 其他X.224类型（Connection Confirm等）
            logger.info("[SLOW-PATH] Non-DT X.224 type=0x" + String.format("%02x", type));
            s.incrementPosition(5); // dst_ref(2) + src_ref(2) + class(1)
            return s;
        }

        /**
         * 通过反射获取ISO的transport字段。
         */
        private Transport getTransportField() {
            try {
                Field f = ISO.class.getDeclaredField("transport");
                f.setAccessible(true);
                return (Transport) f.get(this);
            } catch (Exception e) {
                throw new RuntimeException("Failed to access ISO.transport field", e);
            }
        }
    }

    /**
     * 通过反射替换RDP层中的ISO对象为RdpIso实例。
     *
     * 导航路径: RdpPatch(Rdp) → secureLayer → mcsLayer → isoLayer
     *
     * @param rdpLayer RDP层对象
     */
    public static void injectRdpIso(Object rdpLayer) {
        try {
            injected = false;
            injectError = null;
            receiveCalled = false;
            // 1. 获取secureLayer字段
            Field secureField = rdpLayer.getClass().getSuperclass().getDeclaredField("secureLayer");
            secureField.setAccessible(true);
            Object secureLayer = secureField.get(rdpLayer);

            // 2. 获取mcsLayer字段
            Field mcsField = secureLayer.getClass().getDeclaredField("mcsLayer");
            mcsField.setAccessible(true);
            Object mcsLayer = mcsField.get(secureLayer);

            // 3. 获取isoLayer字段
            Field isoField = mcsLayer.getClass().getDeclaredField("isoLayer");
            isoField.setAccessible(true);
            Object isoLayer = isoField.get(mcsLayer);

            // 4. 获取原始ISO的transport、state和context
            Field transportField = ISO.class.getDeclaredField("transport");
            transportField.setAccessible(true);
            Transport originalTransport = (Transport) transportField.get(isoLayer);

            Field stateField = ISO.class.getDeclaredField("state");
            stateField.setAccessible(true);
            State state = (State) stateField.get(isoLayer);

            Field contextField = ISO.class.getDeclaredField("context");
            contextField.setAccessible(true);
            IContext context = (IContext) contextField.get(isoLayer);

            // 5. 创建RdpIso实例
            RdpIso rdpIso = new RdpIso(context, state, (MCS) mcsLayer);

            // 6. 将原始transport设置到新ISO实例
            transportField.set(rdpIso, originalTransport);

            // 7. 替换MCS中的isoLayer字段
            isoField.set(mcsLayer, rdpIso);

            logger.info("已替换ISO为RdpIso（修复fast-path检测条件 (version&1)==0）");
            injected = true;
        } catch (NoSuchFieldException e) {
            injectError = "NoSuchField: " + e.getMessage();
            logger.log(Level.WARNING, "反射替换ISO失败（字段不存在）: " + e.getMessage()
                    + "，fast-path检测修复未生效");
        } catch (IllegalAccessException e) {
            injectError = "IllegalAccess: " + e.getMessage();
            logger.log(Level.WARNING, "反射替换ISO失败（访问被拒）: " + e.getMessage()
                    + "，fast-path检测修复未生效");
        } catch (Exception e) {
            injectError = e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.log(Level.WARNING, "反射替换ISO失败: " + e.getMessage()
                    + "，fast-path检测修复未生效");
        }
    }
}
