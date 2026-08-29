package com.tangluobo.tomato.rdp;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.io.IO;
import com.tangluobo.tomato.rdp.io.IOSocket;
import com.tangluobo.tomato.rdp.io.SocketIO;
import com.tangluobo.tomato.rdp.layers.ISO;
import com.tangluobo.tomato.rdp.layers.Transport;

/**
 * 修复sshtools rdp库的TLS兼容性问题。
 * 
 * 根本原因：
 * Transport.negotiateSSL()调用SSLContext.getInstance("TLS")自建SSLContext，
 * 然后只调用sslSocket.setEnabledCipherSuites(CIPHERS)设置密码套件，
 * 但从未调用sslSocket.setEnabledProtocols()限制协议版本。
 * 
 * Java 17+默认启用TLS 1.3，SSLSocket会尝试TLS 1.3协商，
 * 而Windows RDP服务器不支持TLS 1.3，直接重置连接（Connection Reset）。
 * 
 * 修复方案（分层防御）：
 * 1. 创建RdpTransport子类覆盖negotiateSSL()，显式设置TLS 1.2协议
 * 2. 原地修改Transport.CIPHERS为现代TLS 1.2密码套件（排除TLS 1.3套件）
 * 3. 设置jdk.tls.client.protocols系统属性和Security属性限制TLS 1.2
 * 4. 设置JVM默认SSLContext使用宽松TrustManager
 */
public class RdpTlsFix {

    private static final Logger logger = Logger.getLogger(RdpTlsFix.class.getName());

    private static volatile boolean applied = false;

    /**
     * 仅TLS 1.2兼容密码套件（优先级从高到低）。
     * 
     * 严格排除TLS 1.3密码套件（TLS_AES_*, TLS_CHACHA20_POLY1305_SHA256），
     * 因为Transport.negotiateSSL()自建SSLContext，即使CIPHERS不包含TLS 1.3套件，
     * 如果SSLSocket的enabledProtocols包含TLSv1.3，握手仍会尝试TLS 1.3。
     */
    private static final String[] MODERN_CIPHERS = {
            // ECDHE + GCM (TLS 1.2, 现代Windows RDP首选)
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            // ECDHE + CHACHA20 (TLS 1.2)
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            // DHE + GCM (TLS 1.2)
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256",
            // ECDHE + CBC (TLS 1.2)
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
            // ECDHE + CBC SHA (TLS 1.2 兼容)
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            // DHE + CBC (TLS 1.2 兼容)
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_DSS_WITH_AES_256_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
            // RSA + GCM/CBC (兼容旧服务器)
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_CBC_SHA256",
            "TLS_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
    };

    /**
     * Transport子类，覆盖negotiateSSL()强制使用TLS 1.2。
     * 
     * Transport.negotiateSSL()的关键缺陷是只调用setEnabledCipherSuites()
     * 而不调用setEnabledProtocols()，导致SSLSocket默认尝试TLS 1.3协商。
     * Windows RDP服务器不支持TLS 1.3，直接重置连接。
     */
    public static class RdpTransport extends Transport {

        private final String hostname;

        public RdpTransport(State state, ISO iso, String hostname) {
            super(state, iso);
            this.hostname = hostname;
        }

        private static final AtomicInteger recvPktCount = new AtomicInteger(0);
        private static final AtomicInteger sendPktCount = new AtomicInteger(0);
        private static volatile java.io.InputStream bcInputStream = null;

        public static int getRecvPktCount() { return recvPktCount.get(); }
        public static int getSendPktCount() { return sendPktCount.get(); }
        public static int getBcInAvailable() {
            try { return bcInputStream != null ? bcInputStream.available() : -1; }
            catch (Exception e) { return -2; }
        }

        @Override
        public void sendPacket(com.tangluobo.tomato.rdp.Packet buffer) throws IOException {
            int count = sendPktCount.incrementAndGet();
            int savePos = buffer.getPosition();
            if (logger.isLoggable(Level.FINEST)) {
                int avail = buffer.getEnd() - savePos;
                int dumpLen = Math.min(avail, 8);
                StringBuilder hexSb = new StringBuilder("[SEND #" + count + "] len=" + buffer.getEnd() + " hex:");
                for (int i = 0; i < dumpLen; i++) {
                    hexSb.append(String.format(" %02x", buffer.get8()));
                }
                buffer.setPosition(savePos);
                logger.finest(hexSb.toString());
            }
            buffer.setPosition(savePos);

            // 修改 ConfirmActive PDU 中的 General Capability Set。
            // 部分 Windows 服务器在未声明该能力时不会回退发送 slow-path 位图，
            // 因此保留 fast-path 输出支持，并由 RdpIsoFix 正确处理低位安全标志。
            try {
                byte[] packet = new byte[buffer.getEnd()];
                buffer.copyToByteArray(packet, 0, 0, packet.length);
                // 只在大包(>150字节，ConfirmActive通常200+字节)中搜索和修改
                if (packet.length > 150) {
                    boolean modified = false;
                    boolean isConfirmActive = false;
                    for (int i = 7; i < packet.length - 24; i++) {
                        if (packet[i] == 0x01 && packet[i+1] == 0x00 &&
                            packet[i+2] == 0x18 && packet[i+3] == 0x00) {
                            isConfirmActive = true;
                            int generalCompressionTypes = (packet[i+12] & 0xff) | ((packet[i+13] & 0xff) << 8);
                            // General Capability Set: extraFlags is at offset 14, not 12.
                            // Offset 12 is generalCompressionTypes. Writing fast-path bits there
                            // accidentally advertised bulk compression that javardp cannot decode.
                            int oldFlags = (packet[i+14] & 0xff) | ((packet[i+15] & 0xff) << 8);
                            int newFlags = oldFlags | 0x0001; // FASTPATH_OUTPUT_SUPPORTED
                            int oldRefresh = packet[i+22] & 0xff;
                            int oldSuppress = packet[i+23] & 0xff;
                            packet[i+14] = (byte)(newFlags & 0xff);
                            packet[i+15] = (byte)((newFlags >> 8) & 0xff);
                            packet[i+22] = 1; // refreshRectSupport = 1
                            packet[i+23] = 1; // suppressOutputSupport = 1
                            modified = true;
                            logger.info(String.format("[CAPS-FIX] General Caps at offset %d: "
                                    + "generalCompressionTypes=0x%04x, extraFlags 0x%04x→0x%04x, "
                                    + "refreshRect %d→1, suppressOutput %d→1",
                                    i, generalCompressionTypes, oldFlags, newFlags, oldRefresh, oldSuppress));
                            break;
                        }
                    }
                    if (modified) {
                        buffer.setPosition(0);
                        buffer.copyFromByteArray(packet, 0, 0, packet.length);
                        buffer.setPosition(savePos);
                    }

                }
            } catch (Exception e) {
                logger.warning("[CAPS-FIX] 修改capabilities失败: " + e.getMessage());
            }

            super.sendPacket(buffer);
            logger.finest("[SEND #" + count + "] flushed");
        }

        @Override
        public com.tangluobo.tomato.rdp.Packet receivePacket(com.tangluobo.tomato.rdp.Packet p, int length) throws IOException {
            com.tangluobo.tomato.rdp.Packet result = super.receivePacket(p, length);
            int count = recvPktCount.incrementAndGet();
            // 诊断：记录Transport层收到的原始数据（前8字节用于判断是否为RDP5 fast-path）
            if (result != null && logger.isLoggable(Level.FINEST)) {
                int savePos = result.getPosition();
                int avail = result.getEnd() - savePos;
                int dumpLen = Math.min(avail, 8);
                StringBuilder hexSb = new StringBuilder("[RECV #" + count + "] len=" + length + " totalAvail=" + avail + " hex:");
                for (int i = 0; i < dumpLen; i++) {
                    hexSb.append(String.format(" %02x", result.get8()));
                }
                result.setPosition(savePos);
                // 检查第一个字节是否为RDP5 fast-path (低2位为0)
                int firstByte = result.get8();
                result.setPosition(savePos);
                boolean isFastPath = (firstByte & 0x03) == 0;
                hexSb.append(String.format(" firstByte=0x%02x fastPath=%b", firstByte, isFastPath));
                logger.finest(hexSb.toString());
            }
            return result;
        }

        @Override
        protected IO negotiateSSL(IO io) throws Exception {
            logger.info("negotiateSSL (Bouncy Castle TLS), securityType=" + getState().getSecurityType());

            // 检查Transport的BufferedInputStream是否预读了属于TLS握手的字节
            byte[] bufferedData = null;
            try {
                Field inField = Transport.class.getDeclaredField("in");
                inField.setAccessible(true);
                Object transportIn = inField.get(this);
                if (transportIn instanceof java.io.DataInputStream) {
                    java.io.DataInputStream din = (java.io.DataInputStream) transportIn;
                    int bufferedBytes = din.available();
                    if (bufferedBytes > 0) {
                        bufferedData = new byte[bufferedBytes];
                        din.readFully(bufferedData);
                        StringBuilder hex = new StringBuilder();
                        for (byte b : bufferedData) hex.append(String.format("%02x ", b));
                        logger.warning("BufferedInputStream预读 " + bufferedBytes + " 字节: " + hex);
                    } else {
                        logger.info("BufferedInputStream无预读数据（available=0）");
                    }
                }
            } catch (Exception e) {
                logger.warning("检查BufferedInputStream失败: " + e.getMessage());
            }

            // 获取底层流的最终输入流（如果有预读数据，先读预读数据再读原始流）
            java.io.InputStream tlsIn;
            java.io.OutputStream tlsOut;
            if (bufferedData != null && bufferedData.length > 0) {
                tlsIn = new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(bufferedData), io.getInputStream());
            } else {
                tlsIn = io.getInputStream();
            }
            tlsOut = io.getOutputStream();

            // 使用Bouncy Castle TLS（完全不同于JSSE的TLS实现）
            // Bouncy Castle直接操作InputStream/OutputStream，不需要包装Socket
            org.bouncycastle.tls.TlsClientProtocol protocol = new org.bouncycastle.tls.TlsClientProtocol(
                    tlsIn, tlsOut);

            // CredSSP需要TLS服务器证书的SubjectPublicKey BIT STRING做通道绑定。
            // BC流式IO不像SSLSocket那样能从SSLSession回取证书，因此在回调中保存。
            final java.util.concurrent.atomic.AtomicReference<byte[]> serverPublicKey =
                    new java.util.concurrent.atomic.AtomicReference<>();

            // 创建TlsCrypto（Bouncy Castle原生加密实现）
            // 重写createCertificate(short, byte[])：返回BcTlsCertificate匿名子类，覆盖supportsKeyUsage()
            //
            // 根本原因分析（通过阅读BC 1.78.1源码）：
            // 1. Certificate.parse() 调用 crypto.createCertificate(certType, derEncoding) — 两参数版本
            //    （之前覆盖的单参数版本根本不会被调用！这就是日志中没有"createCertificate被调用"的原因）
            // 2. BcTlsCertificate extends BcTlsRawKeyCertificate
            // 3. BcTlsRawKeyCertificate.createVerifier() 内部调用 this.validateKeyUsage()
            // 4. validateKeyUsage() 调用 supportsKeyUsage()（虚方法分发）
            // 5. BcTlsCertificate 覆盖了 supportsKeyUsage() 来检查实际KeyUsage扩展
            // 6. RDP服务器证书缺少digitalSignature位 → supportsKeyUsage()返回false → 抛出certificate_unknown(46)
            //
            // 正确方案：覆盖两参数createCertificate，返回BcTlsCertificate子类，覆盖supportsKeyUsage()返回true
            org.bouncycastle.tls.crypto.TlsCrypto crypto = new org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto(
                    new java.security.SecureRandom()) {
                @Override
                public org.bouncycastle.tls.crypto.TlsCertificate createCertificate(short type, byte[] encoding) throws java.io.IOException {
                    logger.info("createCertificate(type=" + type + ", encoding.length=" + encoding.length + ")");

                    if (type != org.bouncycastle.tls.CertificateType.X509) {
                        return super.createCertificate(type, encoding);
                    }

                    // 诊断：打印证书KeyUsage状态
                    try {
                        org.bouncycastle.asn1.x509.Certificate cert =
                                org.bouncycastle.asn1.x509.Certificate.getInstance(encoding);
                        org.bouncycastle.asn1.x509.Extensions exts = cert.getTBSCertificate().getExtensions();
                        if (exts != null) {
                            org.bouncycastle.asn1.x509.KeyUsage ku =
                                    org.bouncycastle.asn1.x509.KeyUsage.fromExtensions(exts);
                            if (ku != null) {
                                byte[] kuBytes = ku.getBytes();
                                int bits = (kuBytes.length > 0) ? (kuBytes[0] & 0xff) : 0;
                                logger.info("证书KeyUsage: bits=0x" + Integer.toHexString(bits) +
                                        " digitalSignature=" + ku.hasUsages(org.bouncycastle.asn1.x509.KeyUsage.digitalSignature));
                            } else {
                                logger.info("证书无KeyUsage扩展");
                            }
                        }
                    } catch (Exception e) {
                        logger.warning("诊断KeyUsage失败: " + e.getClass().getName() + ": " + e.getMessage());
                    }

                    // 创建BcTlsCertificate匿名子类，覆盖supportsKeyUsage()始终返回true
                    // BcTlsCertificate构造函数是public，supportsKeyUsage是protected可被子类覆盖
                    return new org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate(this, encoding) {
                        @Override
                        protected boolean supportsKeyUsage(int keyUsageBits) {
                            logger.info("supportsKeyUsage被调用 keyUsageBits=" + keyUsageBits + ", 已绕过检查");
                            return true;
                        }
                    };
                }
            };

            // 创建TlsClient，配置协议版本（TLS 1.0/1.1/1.2，排除TLS 1.3）
            org.bouncycastle.tls.DefaultTlsClient tlsClient = new org.bouncycastle.tls.DefaultTlsClient(crypto) {
                @Override
                public org.bouncycastle.tls.ProtocolVersion[] getProtocolVersions() {
                    return new org.bouncycastle.tls.ProtocolVersion[]{
                            org.bouncycastle.tls.ProtocolVersion.TLSv12,
                            org.bouncycastle.tls.ProtocolVersion.TLSv11,
                            org.bouncycastle.tls.ProtocolVersion.TLSv10
                    };
                }

                @Override
                public org.bouncycastle.tls.TlsAuthentication getAuthentication() throws java.io.IOException {
                    return new org.bouncycastle.tls.TlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(
                                org.bouncycastle.tls.TlsServerCertificate serverCertificate) throws java.io.IOException {
                            // 信任所有证书（RDP自签名证书）
                            logger.info("服务器证书已接收（信任所有）");
                            try {
                                byte[] certificateDer = serverCertificate.getCertificate()
                                        .getCertificateAt(0).getEncoded();
                                org.bouncycastle.asn1.x509.Certificate certificate =
                                        org.bouncycastle.asn1.x509.Certificate.getInstance(certificateDer);
                                byte[] publicKey = certificate.getSubjectPublicKeyInfo()
                                        .getPublicKeyData().getBytes();
                                if (publicKey.length == 0) {
                                    throw new java.io.IOException("服务器证书公钥为空");
                                }
                                serverPublicKey.set(publicKey);
                                logger.info("已提取CredSSP通道绑定公钥: " + publicKey.length + " 字节");
                            } catch (java.io.IOException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new java.io.IOException("无法从TLS证书提取CredSSP公钥", e);
                            }
                        }

                        @Override
                        public org.bouncycastle.tls.TlsCredentials getClientCredentials(
                                org.bouncycastle.tls.CertificateRequest certificateRequest) throws java.io.IOException {
                            return null; // 无客户端证书
                        }
                    };
                }
            };

            logger.info("Starting Bouncy Castle TLS handshake...");
            protocol.connect(tlsClient);
            logger.info("Bouncy Castle TLS handshake completed");

            // 包装Bouncy Castle的流为IO对象
            final java.io.InputStream bcIn = protocol.getInputStream();
            final java.io.OutputStream bcOut = protocol.getOutputStream();
            bcInputStream = bcIn; // 保存引用用于诊断
            return new IO() {
                @Override public java.io.InputStream getInputStream() throws java.io.IOException { return bcIn; }
                @Override public java.io.OutputStream getOutputStream() throws java.io.IOException { return bcOut; }
                @Override public void closeIO() throws java.io.IOException {
                    bcIn.close();
                    bcOut.close();
                }
                @Override public byte[] getPublicKey() {
                    byte[] key = serverPublicKey.get();
                    return key == null ? null : key.clone();
                }
                @Override public String getAddress() { return hostname != null ? hostname : "unknown"; }
            };
        }

        /**
         * 判断字符串是否为IP地址（IPv4或IPv6）。
         * SNI不适用于IP地址连接。
         */
        private static boolean isIpAddress(String host) {
            return host.chars().allMatch(c -> Character.isDigit(c) || c == '.')
                    || host.contains(":");
        }

        /**
         * 复制Transport的createDefaultTrustManager逻辑。
         * 基类中该方法是private，子类无法访问，需要重新实现。
         */
        private X509TrustManager createDefaultTrustManager() {
            return new X509TrustManager() {
                private final java.util.LinkedList<java.security.cert.X509Certificate> listCert = new java.util.LinkedList<>();

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] arg0, String arg1)
                        throws java.security.cert.CertificateException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    for (java.security.cert.X509Certificate cert : chain) {
                        listCert.add(cert);
                    }
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return listCert.toArray(new java.security.cert.X509Certificate[0]);
                }
            };
        }
    }

    /**
     * 应用RDP TLS兼容性修复。只需调用一次。
     */
    public static synchronized void apply(X509TrustManager trustManager) {
        if (applied) return;

        // 不在生产连接中全局启用 javax.net.debug。它会让整个JVM中的TLS
        // 握手同步输出数千行证书/密码套件信息，多个RDP会话并行时会明显
        // 拖慢重定向切换。需要底层握手诊断时由启动参数显式开启。

        try {
            // 1. 允许TLS 1.0/1.1/1.2协议（排除TLS 1.3，必须在创建SSLContext之前设置）
            enableRdpTlsCiphers();

            // 2. 修复Transport.CIPHERS字段（现代密码套件）
            fixTransportCiphers();

            // 3. 设置系统属性（分层防御，排除TLS 1.3）
            System.setProperty("jdk.tls.client.protocols", "TLSv1,TLSv1.1,TLSv1.2");
            logger.info("已设置系统属性jdk.tls.client.protocols=TLSv1,TLSv1.1,TLSv1.2");

            // 4. 设置JVM默认SSLContext使用宽松TrustManager
            fixDefaultSslContext(trustManager);

            applied = true;
            logger.info("RDP TLS兼容性修复已应用");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "RDP TLS修复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过反射替换ISO对象中的transport字段为RdpTransport实例。
     *
     * 这是最核心的修复：RdpTransport覆盖了negotiateSSL()方法，
     * 使用SSLContext.getInstance("TLSv1.2")并显式设置setEnabledProtocols，
     * 确保SSL握手只使用TLS 1.2协议，避免Windows RDP服务器的Connection Reset。
     * 同时设置SNI（Server Name Indication），避免Windows Server 2012 R2+
     * 因缺少SNI扩展而重置TLS连接。
     *
     * @param rdpLayer RDP层对象，用于导航到ISO层
     * @param hostname RDP服务器主机名（用于SNI，可为null表示无SNI）
     */
    public static void injectRdpTransport(Object rdpLayer, String hostname) {
        try {
            // 导航路径: RdpPatch(Rdp) → secureLayer → mcsLayer → isoLayer → transport
            // 1. 获取secureLayer字段（Rdp类中声明为protected）
            Field secureField = rdpLayer.getClass().getSuperclass().getDeclaredField("secureLayer");
            secureField.setAccessible(true);
            Object secureLayer = secureField.get(rdpLayer);

            // 2. 获取mcsLayer字段（Secure类）
            Field mcsField = secureLayer.getClass().getDeclaredField("mcsLayer");
            mcsField.setAccessible(true);
            Object mcsLayer = mcsField.get(secureLayer);

            // 3. 获取isoLayer字段（MCS类）
            Field isoField = mcsLayer.getClass().getDeclaredField("isoLayer");
            isoField.setAccessible(true);
            Object isoLayer = isoField.get(mcsLayer);

            // 4. 获取transport字段（ISO类）
            Field transportField = isoLayer.getClass().getDeclaredField("transport");
            transportField.setAccessible(true);
            Transport originalTransport = (Transport) transportField.get(isoLayer);

            // 5. 创建RdpTransport并替换
            State state = originalTransport.getState();
            RdpTransport rdpTransport = new RdpTransport(state, (ISO) isoLayer, hostname);
            transportField.set(isoLayer, rdpTransport);

            logger.info("已替换Transport为RdpTransport（强制TLS 1.2协议, SNI=" + hostname + "）");
        } catch (NoSuchFieldException e) {
            logger.log(Level.WARNING, "反射替换Transport失败（字段不存在）: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        } catch (IllegalAccessException e) {
            logger.log(Level.WARNING, "反射替换Transport失败（访问被拒）: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        } catch (Exception e) {
            logger.log(Level.WARNING, "反射替换Transport失败: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        }
    }

    /**
     * 原地修改Transport.CIPHERS数组内容。
     *
     * Transport.CIPHERS是public static final String[]，字段引用为final不可重新赋值，
     * 但数组内容可变。将14个旧版SSL/TLS 1.0密码套件替换为现代TLS 1.2密码套件。
     */
    private static void fixTransportCiphers() {
        try {
            // 先过滤出JVM实际支持的密码套件
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            Set<String> supported = new HashSet<>();
            try (SSLSocket socket = (SSLSocket)
                    ctx.getSocketFactory().createSocket()) {
                supported.addAll(Arrays.asList(socket.getSupportedCipherSuites()));
            }

            // 按优先级过滤，保持顺序，严格排除TLS 1.3密码套件
            List<String> filtered = new ArrayList<>();
            for (String cipher : MODERN_CIPHERS) {
                if (supported.contains(cipher) && !filtered.contains(cipher)) {
                    filtered.add(cipher);
                }
            }

            if (filtered.isEmpty()) {
                logger.warning("JVM不支持任何现代RDP密码套件");
                return;
            }

            // 原地修改CIPHERS数组内容（字段为final，但数组内容可变）
            String[] ciphers = Transport.CIPHERS;
            for (int i = 0; i < ciphers.length; i++) {
                ciphers[i] = i < filtered.size() ? filtered.get(i) : filtered.get(0);
            }

            logger.info("已修改Transport.CIPHERS(原地): " + Math.min(filtered.size(), ciphers.length)
                    + "个TLS 1.2密码套件");
            logger.fine("密码套件: " + Arrays.toString(ciphers));
        } catch (Exception e) {
            logger.log(Level.WARNING, "修改Transport.CIPHERS失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设置JVM默认SSLContext使用宽松TrustManager，接受RDP服务器自签名证书。
     */
    private static void fixDefaultSslContext(X509TrustManager trustManager) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
            SSLContext.setDefault(ctx);
            logger.info("已设置默认SSLContext（TLS + 宽松TrustManager）");
        } catch (Exception e) {
            logger.log(Level.WARNING, "设置默认SSLContext失败: " + e.getMessage());
        }
    }

    /**
     * 允许RDP协议所需的TLS密码套件和协议版本。
     *
     * Windows RDP服务器对TLS 1.3支持不完善，可能导致Connection Reset，
     * 因此排除TLS 1.3。旧版Windows服务器（2003/2008）可能只支持TLS 1.0，
     * 因此也移除TLSv1/TLSv1.1的禁用限制。同时移除DHE限制以兼容更多密码套件。
     */
    private static void enableRdpTlsCiphers() {
        try {
            // 允许TLS 1.0/1.1/1.2（排除TLS 1.3），兼容所有Windows RDP服务器版本
            java.security.Security.setProperty("jdk.tls.client.protocols", "TLSv1,TLSv1.1,TLSv1.2");
            logger.info("已设置TLS客户端协议为TLSv1,TLSv1.1,TLSv1.2（Security属性）");

            // 从禁用列表中移除DHE和TLSv1/TLSv1.1限制
            String disabled = java.security.Security.getProperty("jdk.tls.disabledAlgorithms");
            if (disabled != null) {
                StringBuilder sb = new StringBuilder();
                for (String item : disabled.split(",")) {
                    String trimmed = item.trim();
                    if (trimmed.isEmpty()) continue;
                    // 移除DHE相关限制
                    if (trimmed.startsWith("DH ") || trimmed.equals("DHE_DSS") || trimmed.equals("DHE_RSA")) {
                        logger.fine("移除TLS禁用项: " + trimmed);
                        continue;
                    }
                    // 移除TLSv1/TLSv1.1限制（旧版Windows RDP服务器需要）
                    if (trimmed.equals("TLSv1") || trimmed.equals("TLSv1.1")) {
                        logger.fine("移除TLS禁用项: " + trimmed);
                        continue;
                    }
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(trimmed);
                }
                java.security.Security.setProperty("jdk.tls.disabledAlgorithms", sb.toString());
                logger.info("已调整TLS禁用算法以兼容RDP协议: " + sb);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "调整TLS安全属性失败: " + e.getMessage());
        }
    }
}
