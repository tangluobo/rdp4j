package com.tangluobo.tomato.rdp;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;
import javax.swing.JComponent;

import com.tangluobo.tomato.rdp.graphics.RdesktopCanvas;
import com.tangluobo.tomato.rdp.io.DefaultIO;
import com.tangluobo.tomato.rdp.keymapping.KeyCode_FileBased;
import com.tangluobo.tomato.rdp.layers.Rdp;
import com.tangluobo.tomato.rdp.layers.nla.HResultException;
import com.tangluobo.tomato.rdp.rdp5.VChannels;
import com.tangluobo.tomato.rdp.clipboard.FixedClipChannel;

/**
 * RDP客户端封装类，基于com.sshtools:rdp库
 * 提供连接、断开、状态查询等功能，支持NLA认证和多会话
 */
public class RdpClient {

    private static final Logger logger = Logger.getLogger(RdpClient.class.getName());

    static {
        try {
            // RDP图形和音频都是高频数据，诊断日志必须限量滚动，不能让
            // 同步文件I/O反过来阻塞协议接收线程。
            String pattern = Paths.get("rdp-debug-%g.log").toAbsolutePath().toString();
            FileHandler handler = new FileHandler(pattern, 16 * 1024 * 1024, 3, true);
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.INFO);
            Logger.getLogger("").addHandler(handler);
        } catch (Exception e) {
            logger.log(Level.WARNING, "无法初始化 RDP 诊断日志", e);
        }
    }

    private volatile boolean connected = false;
    private volatile Rdp rdpLayer;
    private RdesktopCanvas canvas;
    private State state;
    private Options options;
    private Consumer<String> onDisconnected;
    private Consumer<Void> onConnected;
    private Consumer<Void> onFirstFrame;
    private Thread rdpThread;
    private volatile boolean mapClipboard = true;
    private volatile boolean soundEnabled = true;
    private volatile RdpsndChannel rdpsndChannel;
    private final AtomicLong attemptSequence = new AtomicLong();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();
    private volatile long currentAttemptId;

    /**
     * 嵌入式IContext实现，不创建独立窗口
     */
    private class EmbeddedContext implements IContext {
        private final long attemptId;
        private final State attemptState;
        private volatile RdesktopCanvas attemptCanvas;
        private volatile boolean loggedOn = false;
        private volatile boolean ready = false;

        EmbeddedContext(long attemptId, State attemptState) {
            this.attemptId = attemptId;
            this.attemptState = attemptState;
        }

        void bindCanvas(RdesktopCanvas attemptCanvas) {
            this.attemptCanvas = attemptCanvas;
        }

        private boolean isCurrentAttempt() {
            return attemptId == currentAttemptId;
        }

        @Override
        public void dispose() {
            if (isCurrentAttempt()) {
                connected = false;
                shutdownSoundChannel();
            }
        }

        @Override
        public void error(Exception e, boolean sysexit) {
            if (sysexit && !isCurrentAttempt()) {
                logger.fine("忽略已被替换的RDP连接错误, attempt=" + attemptId);
                return;
            }
            logger.log(Level.SEVERE, "RDP错误: " + e.getMessage(), e);
            if (sysexit) {
                notifyDisconnected("连接错误: " + describeException(e));
            }
        }

        @Override
        public byte[] loadLicense() throws java.io.IOException {
            // 暂不支持许可证持久化
            return null;
        }

        @Override
        public void saveLicense(byte[] license) throws java.io.IOException {
            // 暂不支持许可证持久化
        }

        @Override
        public void screenResized(int width, int height, boolean clientInitiated) {
            logger.info("屏幕大小变更: " + width + "x" + height);
        }

        @Override
        public void setLoggedOn() {
            loggedOn = true;
            logger.info("RDP登录成功");
        }

        @Override
        public void toggleFullScreen() {
            // 嵌入模式不支持全屏切换
        }

        @Override
        public void ready(ReadyType readyType) {
            if (!isCurrentAttempt()) {
                logger.fine("忽略已被替换的RDP连接ready回调, attempt=" + attemptId);
                return;
            }
            logger.info("RDP ready回调: " + readyType);
            // 关键：必须调用canvas.triggerReady()！
            // RdesktopFrame的标准实现会调用canvas.triggerReady(ready)，
            // 这在INPUT阶段触发input.triggerReadyToSend()→doLockKeys()，
            // 发送CapsLock/NumLock同步键事件，这是服务器开始推送画面的前提条件。
            // 不调用triggerReady(INPUT)会导致服务器不推送画面数据（黑屏）。
            RdesktopCanvas targetCanvas = attemptCanvas;
            if (targetCanvas != null) {
                targetCanvas.triggerReady(readyType);
            }
            if (readyType == ReadyType.DISPLAY) {
                ready = true;
                connected = true;
                logger.info("RDP桌面就绪，触发onConnected回调, rdp5=" + attemptState.isRDP5());
                if (onConnected != null) {
                    onConnected.accept(null);
                }
            }
        }

        public boolean isLoggedOn() {
            return loggedOn;
        }

        public boolean isReady() {
            return ready;
        }
    }

    public RdpClient() {
    }

    /**
     * 连接到RDP服务器（异步，连接就绪后通过onConnected回调通知）
     *
     * @param host         服务器地址
     * @param port         服务器端口（通常3389）
     * @param username     用户名
     * @param password     密码
     * @param domain       域名（可为null）
     * @param width        桌面宽度
     * @param height       桌面高度
     * @param bpp          色深（16/24）
     * @param useSsl       是否使用SSL/TLS加密（无TLS服务器需设为false）
     * @param mapClipboard 是否启用剪贴板同步（本地与远程桌面互拷文本）
     * @param enableSound
     */
    public void connect(String host, int port, String username, String password,
                        String domain, int width, int height, int bpp, boolean useSsl,
                        boolean mapClipboard, boolean enableSound) {
        if (connected) {
            throw new IllegalStateException("RDP客户端已连接，请先断开当前连接");
        }
        disconnectNotified.set(false);
        retireCurrentAttempt();
        this.mapClipboard = mapClipboard;
        this.soundEnabled = enableSound;

        // 生产连接只记录状态和错误；FINE/HexDump会在RDP收包线程同步生成
        // 巨量文本，造成图形和音频包分钟级积压。
        Logger sshtools = Logger.getLogger("com.tangluobo.tomato.rdp");
        sshtools.setLevel(Level.INFO);
        Logger root = Logger.getLogger("");
        for (java.util.logging.Handler h : root.getHandlers()) {
            h.setLevel(Level.INFO);
        }

        // 创建配置
        options = new Options();
        options.setWidth(width);
        options.setHeight(height);
        // RDPGFX uses XRGB/ARGB 32-bit surfaces, and GNOME Remote Desktop
        // rejects a client that advertises the Graphics Pipeline with a lower
        // colour depth. The legacy setting remains an input compatibility
        // option; the wire session is promoted to 32 bpp.
        options.setBpp(32);
        options.setRdp5(true);
        options.setPacketEncryption(true);
        options.setBitmapCaching(true);
        options.setMapClipboard(mapClipboard);
        options.setLowLatency(true);

        // 绝不能在正常会话中打开：每个屏幕/音频包都会同步写完整十六进制。
        options.setDebugHexdump(false);

        // 配置安全类型。启用增强安全时在第一次X.224协商中同时声明TLS和
        // CredSSP/NLA，并把HYBRID放到最后作为首选。这样强制NLA的服务器会
        // 直接选中HYBRID，不再先建立一条必然失败的纯TLS连接再立即重连。
        // GNOME Remote Desktop 42在前一条连接尚未完全清理时可能拒绝第二次
        // 桌面会话创建，并以ERRINFO_RPC_INITIATED_DISCONNECT(0x0001)终止它。
        // 不要求NLA的服务器仍可从同一个声明中选择SSL；只支持Standard的
        // 老服务器则沿用ISO层的无协商降级路径。
        options.getSecurityTypes().clear();
        options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);
        if (useSsl) {
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.HYBRID);
        }

        // 设置宽松的TrustManager：接受RDP服务器自签名证书
        X509TrustManager permissiveTrustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        options.setTrustManager(permissiveTrustManager);

        // 应用RDP TLS兼容性修复（替换Transport.CIPHERS + 设置SSLContext + 移除安全限制）
        RdpTlsFix.apply(permissiveTrustManager);

        // 创建凭证
        DefaultCredentialsProvider dcp = new DefaultCredentialsProvider();
        if (username != null) dcp.setUsername(username);
        if (password != null) dcp.setPassword(password.toCharArray());
        if (domain != null && !domain.isEmpty()) dcp.setDomain(domain);

        // 创建状态（使用RdpState阻止processGeneralCaps错误禁用RDP5）
        RdpState rdpState = new RdpState(options);
        rdpState.lockRdp5(); // 在连接前锁定RDP5状态
        state = rdpState;

        // 加载键盘映射（keymaps在rdp JAR的根目录下）
        String keyMapPath = "keymaps/";
        String mapFile = "en-us";
        try {
            // 尝试从rdp JAR的classpath加载
            URL resource = KeyCode_FileBased.class.getResource("/" + keyMapPath + mapFile);
            if (resource == null) {
                // 备选：用ClassLoader加载
                resource = ClassLoader.getSystemResource(keyMapPath + mapFile);
            }
            if (resource != null) {
                InputStream istr = resource.openStream();
                if (istr != null) {
                    KeyCode_FileBased keyMap = new KeyCode_FileBased(options, resource, istr);
                    options.setKeymap(keyMap);
                    istr.close();
                    logger.info("键盘映射加载成功: " + mapFile);
                }
            } else {
                logger.warning("未找到键盘映射文件: " + keyMapPath + mapFile + "，使用默认映射");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "加载键盘映射失败: " + e.getMessage());
        }

        // 创建虚拟通道（FixedVChannels修复库分片重组NPE：大消息分片即断连）
        VChannels channels = new FixedVChannels(state);

        // 创建嵌入式Context
        long attemptId = beginAttempt();
        EmbeddedContext context = new EmbeddedContext(attemptId, state);

        // 创建画布（不在SwingNode中显示，直到ready回调触发）
        canvas = new RdesktopCanvas(context, state);
        context.bindCanvas(canvas);
        state.setCanvas(canvas);

        // Windows只在客户端声明RDPDR时启动RDPSND服务端。
        registerRdpdrChannel(channels);

        // 注册剪贴板同步通道（含焦点监听，焦点切换时同步本地/远程剪贴板内容）
        registerClipboardChannel(state, canvas, channels);

        // 注册音频重定向通道（保持MSTSC典型静态通道顺序）
        registerSoundChannel(channels);
        registerGraphicsChannel(channels);

        // 创建RDP层（使用RdpPatch修复rdp5_process加密bug）
        rdpLayer = new RdpPatch(context, state, channels);
        configureFrameCallback((RdpPatch) rdpLayer, attemptId);

        // 核心修复1：通过反射替换Transport为RdpTransport
        // RdpTransport覆盖negotiateSSL()方法，强制使用TLS 1.2协议
        // 这解决了Transport.negotiateSSL()未调用setEnabledProtocols()
        // 导致SSLSocket尝试TLS 1.3而被Windows RDP服务器Connection Reset的问题
        // 同时传入hostname用于SNI（Server Name Indication），避免Windows Server 2012 R2+
        // 因缺少SNI扩展而重置TLS连接
        RdpTlsFix.injectRdpTransport(rdpLayer, host);

        // javardp 3.0.0 把带 FASTPATH_OUTPUT_ENCRYPTED 标志（0x02）的包误判为 slow-path。
        // 替换接收器，使其按 action 位而不是完整低两位识别 fast-path。
        RdpIsoFix.injectRdpIso(rdpLayer);

        // 在新线程中执行RDP连接（connect+mainLoop是阻塞调用）
        rdpThread = new Thread(() -> {
            try {
                logger.info("开始RDP连接: " + host + ":" + port + " useSsl=" + useSsl);
                rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                        dcp, options.getCommand(), options.getDirectory());
                // TLS/HYBRID仍会在Demand Active之前通过Basic Security Header
                // 发送服务器许可PDU。必须由Licence.process()在收到有效许可
                // 结果后更新licenceIssued，不能在connect()返回时提前跳过。
                logger.info("RDP协议握手完成，进入主循环: " + host + ":" + port);
                rdpLayer.mainLoop();
                logger.info("RDP主循环正常退出");
                notifyDisconnected("服务器已关闭连接");
            } catch (RdpRedirectionException e) {
                // HYBRID is now negotiated on the first transport, so GNOME's
                // RDSTLS handover can arrive here instead of in the historical
                // HYBRID fallback method. Continue with the same bounded
                // redirection state machine rather than exposing this internal
                // control-flow packet as a connection error.
                logger.info("首次HYBRID连接收到服务端会话重定向，进入重定向兼容流程: "
                        + e.getMessage());
                retryWithHybridSecurity(host, port, dcp, e);
            } catch (RdesktopDisconnectException e) {
                logger.info("RDP连接断开: " + e.getMessage());
                notifyDisconnected(describeDisconnect(e));
            } catch (RdesktopLicenseException e) {
                logger.log(Level.SEVERE, "RDP许可证错误: " + e.getMessage());
                notifyDisconnected("许可证错误: " + e.getMessage());
            } catch (RdesktopException e) {
                // SSL协商失败且当前使用SSL时，自动回退到STANDARD重试
                if (useSsl && e.getMessage() != null && e.getMessage().contains("SSL negotiation failed")) {
                    logger.warning("SSL协商失败，尝试回退到Standard RDP Security（无TLS）重连...");
                    retryWithStandardSecurity(host, port, dcp);
                    return;
                }
                logger.log(Level.SEVERE, "RDP异常: " + e.getMessage(), e);
                notifyDisconnected("连接异常: " + e.getMessage());
            } catch (java.net.UnknownHostException e) {
                logger.log(Level.SEVERE, "无法解析主机: " + host);
                notifyDisconnected("无法解析主机: " + host);
            } catch (java.net.ConnectException e) {
                logger.log(Level.SEVERE, "连接被拒绝: " + e.getMessage());
                notifyDisconnected("连接被拒绝: " + host + ":" + port + " - " + e.getMessage());
            } catch (java.net.SocketException e) {
                if (connected) {
                    logger.log(Level.WARNING, "连接中断: " + e.getMessage());
                    notifyDisconnected("连接中断: " + e.getMessage());
                }
            } catch (java.io.IOException e) {
                String msg = e.getMessage();
                // SSL_NOT_ALLOWED_BY_SERVER错误：服务器不支持SSL，回退到Standard RDP Security
                if (useSsl && msg != null && msg.contains("SSL_NOT_ALLOWED_BY_SERVER")) {
                    logger.warning("服务器不支持SSL，回退到Standard RDP Security重连...");
                    retryWithStandardSecurity(host, port, dcp);
                    return;
                }
                if (useSsl && msg != null && msg.contains("HYBRID_REQUIRED_BY_SERVER")) {
                    logger.warning("服务器强制NLA，使用HYBRID（CredSSP）重连...");
                    retryWithHybridSecurity(host, port, dcp);
                    return;
                }
                // SSL_REQUIRED_BY_SERVER错误：服务器要求SSL/TLS，回退到SSL/TLS加密重连
                if (!useSsl && msg != null && msg.contains("SSL_REQUIRED_BY_SERVER")) {
                    logger.warning("服务器要求Enhanced RDP Security（TLS），回退到SSL/TLS加密重连...");
                    retryWithSslSecurity(host, port, dcp);
                    return;
                }
                String detail = describeException(e);
                logger.log(Level.SEVERE, "IO错误: " + detail, e);
                notifyDisconnected("IO错误: " + detail);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "RDP连接错误: " + e.getMessage(), e);
                notifyDisconnected("连接错误: " + e.getMessage());
            } finally {
                connected = false;
            }
        }, "RDP-" + host);
        rdpThread.setDaemon(true);
        rdpThread.start();

        // 诊断：定期报告RDP状态（持续30秒，每5秒一次）
        java.util.Timer diagTimer = new java.util.Timer("RDP-Diag", true);
        final int[] count = {0};
        diagTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                count[0]++;
                if (count[0] > 6 || rdpThread == null || !rdpThread.isAlive()) {
                    cancel();
                    return;
                }
                if (rdpLayer instanceof RdpPatch) {
                    RdpPatch patch = (RdpPatch) rdpLayer;
                    logger.info(String.format("[DIAG #%d] totalPDUs=%d, bitmapUpdates=%d, rdp5Packets=%d, sent=%d, recv=%d, connected=%b, active=%b, rdp5=%b, licenceIssued=%b, securityType=%s",
                            count[0], patch.getTotalPduCount(), patch.getBitmapUpdateCount(), patch.getRdp5PacketCount(),
                            RdpTlsFix.RdpTransport.getSendPktCount(), RdpTlsFix.RdpTransport.getRecvPktCount(),
                            rdpLayer.isConnected(), state.isActive(), state.isRDP5(), state.isLicenceIssued(),
                            state.getSecurityType()));
                }
            }
        }, 5000, 5000);
    }

    /**
     * 注册剪贴板同步虚拟通道（启用时）。
     * 注意：回退重连路径会重建VChannels/Canvas，必须重新注册，否则回退后剪贴板同步失效。
     */
    private void registerClipboardChannel(State state, RdesktopCanvas canvas, VChannels channels) {
        if (!mapClipboard || !state.isRDP5()) {
            return;
        }
        try {
            // FixedClipChannel：修复库内clipboard未初始化（远程粘贴不可用）及
            // UnicodeHandler编码bug（中文乱码）、远程→本地格式选择问题
            FixedClipChannel clipChannel = new FixedClipChannel();
            channels.register(clipChannel);
            ((JComponent) canvas.getDisplay()).addFocusListener(clipChannel);
            logger.info("剪贴板同步通道已注册");
        } catch (RdesktopException e) {
            logger.log(Level.WARNING, "注册剪贴板通道失败: " + e.getMessage());
        }
    }

    /**
     * 注册RDPDR声明通道。Windows以该通道的存在作为启动RDPSND的前提。
     */
    private void registerRdpdrChannel(VChannels channels) {
        if (!soundEnabled) {
            logger.info("远程音频未启用，跳过设备重定向声明通道(rdpdr)");
            return;
        }
        try {
            channels.register(new RdpdrChannel());
            logger.info("设备重定向声明通道(rdpdr)已注册");
        } catch (RdesktopException e) {
            logger.log(Level.WARNING, "注册rdpdr通道失败: " + e.getMessage());
        }
    }

    /**
     * 注册音频重定向虚拟通道（rdpsnd，MS-RDPEA）。
     * javardp库本身无rdpsnd实现，由{@link RdpsndChannel}完整实现
     * 格式协商、训练应答、两段式音频接收与WAVECONFIRM流控。
     * 注意：回退重连路径会重建VChannels，必须重新注册。
     */
    private void registerSoundChannel(VChannels channels) {
        shutdownSoundChannel(); // 重连路径：先释放旧通道的播放线程
        if (!soundEnabled) {
            logger.info("远程音频通道(rdpsnd)未启用");
            return;
        }
        try {
            rdpsndChannel = new RdpsndChannel();
            channels.register(rdpsndChannel);
            logger.info("音频重定向通道(rdpsnd)已注册");
        } catch (RdesktopException e) {
            logger.log(Level.WARNING, "注册音频通道失败: " + e.getMessage());
        }
    }

    /** Registers the MS-RDPEDYC transport used by the RDP Graphics Pipeline. */
    private void registerGraphicsChannel(VChannels channels) {
        try {
            channels.register(new DrdynvcChannel(() -> {
                Consumer<Void> callback = onFirstFrame;
                if (callback != null) {
                    callback.accept(null);
                }
            }));
            logger.info("动态图形通道(drdynvc/rdpgfx)已注册");
        } catch (RdesktopException e) {
            logger.log(Level.WARNING, "注册动态图形通道失败: " + e.getMessage());
        }
    }

    /** 停止音频通道播放并释放资源（断开/重连时调用） */
    private void shutdownSoundChannel() {
        RdpsndChannel ch = rdpsndChannel;
        rdpsndChannel = null;
        if (ch != null) {
            ch.shutdown();
        }
    }

    private void notifyDisconnected(String reason) {
        connected = false;
        shutdownSoundChannel();
        if (disconnectNotified.compareAndSet(false, true) && onDisconnected != null) {
            onDisconnected.accept(reason);
        }
    }

    private long beginAttempt() {
        connected = false;
        shutdownSoundChannel();
        long attemptId = attemptSequence.incrementAndGet();
        currentAttemptId = attemptId;
        return attemptId;
    }

    private void retireCurrentAttempt() {
        connected = false;
        currentAttemptId = attemptSequence.incrementAndGet();
    }

    /**
     * 断开RDP连接
     */
    public void disconnect() {
        shutdownSoundChannel();
        if (!connected && (rdpLayer == null || !rdpLayer.isConnected())) return;
        disconnectNotified.set(true);
        retireCurrentAttempt();
        connected = false;
        try {
            if (rdpLayer != null && rdpLayer.isConnected()) {
                rdpLayer.disconnect();
                logger.info("RDP已断开");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "断开RDP连接时出错: " + e.getMessage());
        }
    }

    /**
     * SSL协商失败后，回退到Standard RDP Security（无TLS）重试连接。
     * 重新创建所有RDP层对象，仅使用STANDARD安全类型。
     */
    private void retryWithStandardSecurity(String host, int port, CredentialProvider dcp) {
        try {
            // 断开之前的连接
            if (rdpLayer != null && rdpLayer.isConnected()) {
                try { rdpLayer.disconnect(); } catch (Exception ignored) {}
            }

            // 重新配置：仅STANDARD安全类型
            options.getSecurityTypes().clear();
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);
            // 注意：STANDARD放在最后，这样State构造函数会选择STANDARD
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);

            // 重新创建状态
            RdpState rdpState = new RdpState(options);
            rdpState.lockRdp5();
            state = rdpState;

            // 重新创建画布
            long attemptId = beginAttempt();
            EmbeddedContext context = new EmbeddedContext(attemptId, state);
            canvas = new RdesktopCanvas(context, state);
            context.bindCanvas(canvas);
            state.setCanvas(canvas);

            // 重新创建RDP层（FixedVChannels修复库分片重组NPE）
            VChannels channels = new FixedVChannels(state);
            registerRdpdrChannel(channels);
            // 回退重连后重新注册剪贴板通道（重建VChannels/Canvas后原注册已丢失）
            registerClipboardChannel(state, canvas, channels);
            registerSoundChannel(channels);
            registerGraphicsChannel(channels);
            rdpLayer = new RdpPatch(context, state, channels);
            configureFrameCallback((RdpPatch) rdpLayer, attemptId);

            // 注入RdpTransport（仅当服务器支持SSL时才需要）
            RdpTlsFix.injectRdpTransport(rdpLayer, host);

            RdpIsoFix.injectRdpIso(rdpLayer);


            logger.info("回退重连: " + host + ":" + port + " securityType=STANDARD");
            rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                    dcp, options.getCommand(), options.getDirectory());
            logger.info("Standard RDP Security连接成功，进入主循环");
            rdpLayer.mainLoop();
            logger.info("RDP主循环正常退出");
            notifyDisconnected("服务器已关闭连接");
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(describeDisconnect(e));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Standard RDP Security重连也失败: " + e.getMessage(), e);
            notifyDisconnected("连接失败（SSL和Standard均不可用）: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    /**
     * 服务器要求SSL/TLS（SSL_REQUIRED_BY_SERVER）时，回退到SSL/TLS加密重试连接。
     * 重新创建所有RDP层对象，使用SSL作为优先安全类型。
     */
    private void retryWithSslSecurity(String host, int port, CredentialProvider dcp) {
        try {
            // 断开之前的连接
            if (rdpLayer != null && rdpLayer.isConnected()) {
                try { rdpLayer.disconnect(); } catch (Exception ignored) {}
            }

            // 重新配置：STANDARD在前，SSL在末尾（State构造函数取最后一个元素作为初始securityType）
            options.getSecurityTypes().clear();
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);

            // 重新创建状态
            RdpState rdpState = new RdpState(options);
            rdpState.lockRdp5();
            state = rdpState;

            // 重新创建画布
            long attemptId = beginAttempt();
            EmbeddedContext context = new EmbeddedContext(attemptId, state);
            canvas = new RdesktopCanvas(context, state);
            context.bindCanvas(canvas);
            state.setCanvas(canvas);

            // 重新创建RDP层（FixedVChannels修复库分片重组NPE）
            VChannels channels = new FixedVChannels(state);
            registerRdpdrChannel(channels);
            // 回退重连后重新注册剪贴板通道（重建VChannels/Canvas后原注册已丢失）
            registerClipboardChannel(state, canvas, channels);
            registerSoundChannel(channels);
            registerGraphicsChannel(channels);
            rdpLayer = new RdpPatch(context, state, channels);
            configureFrameCallback((RdpPatch) rdpLayer, attemptId);

            // 注入RdpTransport（强制TLS 1.2 + SNI，修复SSLSocket默认TLS 1.3被服务器Reset的问题）
            RdpTlsFix.injectRdpTransport(rdpLayer, host);

            RdpIsoFix.injectRdpIso(rdpLayer);


            logger.info("回退重连: " + host + ":" + port + " securityType=SSL");
            rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                    dcp, options.getCommand(), options.getDirectory());

            logger.info("SSL/TLS连接成功，进入主循环");
            rdpLayer.mainLoop();
            logger.info("RDP主循环正常退出");
            notifyDisconnected("服务器已关闭连接");
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(describeDisconnect(e));
        } catch (Exception e) {
            // SSL/TLS握手失败时，尝试使用HYBRID（CredSSP/NLA）重连
            // 很多Windows服务器要求NLA（网络级别认证），不支持纯TLS连接
            String msg = e.getMessage();
            if (msg != null && msg.contains("SSL negotiation failed")) {
                logger.warning("SSL/TLS握手失败，尝试使用HYBRID（CredSSP/NLA）重连...");
                retryWithHybridSecurity(host, port, dcp);
                return;
            }
            logger.log(Level.SEVERE, "SSL/TLS重连也失败: " + e.getMessage(), e);
            notifyDisconnected("连接失败（Standard和SSL均不可用）: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    /**
     * 使用HYBRID（CredSSP/NLA）安全类型重试连接。
     * 当SSL/TLS握手失败时，服务器可能要求NLA（网络级别认证）而非纯TLS。
     * HYBRID在TLS握手后进行CredSSP（NTLM/Kerberos）认证。
     */
    private void retryWithHybridSecurity(String host, int port, CredentialProvider dcp) {
        retryWithHybridSecurity(host, port, dcp, null);
    }

    /**
     * Runs the HYBRID main loop and follows Server Redirection PDUs. When
     * {@code initialRedirection} is non-null, the first HYBRID connection was
     * already established by the normal connection path and only its handover
     * packet remains to be processed.
     */
    private void retryWithHybridSecurity(String host, int port, CredentialProvider dcp,
                                         RdpRedirectionException initialRedirection) {
        Exception rawNtlmFailure = null;
        int redirectCount = 0;
        String currentHost = host;
        int currentPort = port;
        CredentialProvider currentCredentials = dcp;
        try {
            RdpRedirectionException pendingRedirection = initialRedirection;
            if (pendingRedirection == null) {
                rawNtlmFailure = connectHybridWithTokenFallback(
                        currentHost, currentPort, currentCredentials);
            }

            while (true) {
                if (pendingRedirection == null) {
                    try {
                        logger.info("HYBRID (CredSSP/NLA) 连接成功，进入主循环");
                        rdpLayer.mainLoop();
                        logger.info("RDP主循环正常退出");
                        notifyDisconnected("服务器已关闭连接");
                        return;
                    } catch (RdpRedirectionException redirectException) {
                        pendingRedirection = redirectException;
                    }
                }

                RdpRedirectionException redirectException = pendingRedirection;
                pendingRedirection = null;
                    // The redirect PDU terminates this transport. Invalidate its
                    // callbacks immediately, before parsing credentials or opening
                    // the replacement connection, so queued AWT input cannot report
                    // the old TLS stream as a new disconnect.
                    retireCurrentAttempt();
                    RdpRedirectionInfo redirect = redirectException.getRedirection();
                    if (++redirectCount > 6) {
                        throw new RdesktopException("服务端重定向次数过多（超过6次）");
                    }

                    if (redirect.isPasswordPkEncrypted()) {
                        if (!redirect.hasFlag(RdpRedirectionInfo.LB_USERNAME)
                                || !redirect.hasFlag(RdpRedirectionInfo.LB_PASSWORD)
                                || !redirect.hasFlag(RdpRedirectionInfo.LB_REDIRECTION_GUID)) {
                            throw new RdesktopException(
                                    "RDSTLS重定向缺少一次性用户名、加密密码或Redirection GUID");
                        }

                        // Keep the encrypted password opaque. The source GNOME
                        // service produced it for the destination certificate;
                        // RDSTLS replays it together with the redirection GUID.
                        RdstlsCredentials rdstlsCredentials = new RdstlsCredentials(
                                redirect.getDomain(), redirect.getUsername(),
                                redirect.getRedirectionGuid(), redirect.getPassword());
                        currentHost = redirect.selectTargetHost(currentHost);
                        options.setRoutingToken(redirect.getLoadBalanceInfo());
                        logger.info("跟随RDSTLS服务端重定向: target=" + currentHost + ":" + currentPort
                                + ", routingToken="
                                + (redirect.getLoadBalanceInfo() == null
                                        ? 0 : redirect.getLoadBalanceInfo().length)
                                + " bytes, hop=" + redirectCount);
                        connectRdstlsAttempt(currentHost, currentPort, rdstlsCredentials);
                        continue;
                    } else {
                        if (!redirect.hasFlag(RdpRedirectionInfo.LB_USERNAME)
                                || !redirect.hasFlag(RdpRedirectionInfo.LB_PASSWORD)) {
                            throw new RdesktopException("Server Redirection缺少一次性用户名或密码");
                        }

                        char[] redirectPassword = redirect.getClearTextPassword();
                        DefaultCredentialsProvider redirectedCredentials =
                                new DefaultCredentialsProvider(
                                        redirect.getDomain(), redirect.getUsername(), redirectPassword);
                        currentHost = redirect.selectTargetHost(currentHost);
                        currentCredentials = redirectedCredentials;
                        options.setRoutingToken(redirect.getLoadBalanceInfo());
                        logger.info("跟随RDP服务端重定向: target=" + currentHost + ":" + currentPort
                                + ", routingToken="
                                + (redirect.getLoadBalanceInfo() == null
                                        ? 0 : redirect.getLoadBalanceInfo().length)
                                + " bytes, hop=" + redirectCount);
                    }

                    rawNtlmFailure = connectHybridWithTokenFallback(
                            currentHost, currentPort, currentCredentials);
            }
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(describeDisconnect(e));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "RDP安全会话重定向/重连失败: " + e.getMessage(), e);
            String detail;
            if (options.getRdstlsCredentials() != null) {
                // Do not mislabel a second-hop RDSTLS protocol failure as an
                // NTLM/SPNEGO authentication failure from the first hop.
                detail = "RDSTLS=" + describeException(e);
            } else if (rawNtlmFailure == null) {
                detail = describeHybridFailure(e, dcp);
            } else {
                detail = "首选NTLM封装=" + describeHybridFailure(rawNtlmFailure, dcp)
                        + "；回退NTLM封装=" + describeHybridFailure(e, dcp);
            }
            notifyDisconnected("连接失败（Standard、SSL、HYBRID和RDSTLS均不可用）: " + detail);
        } finally {
            // Redirection credentials and routing data are one-shot. Do not let them
            // leak into the next user-initiated connection on this RdpClient.
            options.setRoutingToken(null);
            options.setRdstlsCredentials(null);
            connected = false;
        }
    }

    /**
     * Connect once with the configured NTLM token format, falling back to the
     * other wrapper only for an actual token-format failure.
     */
    private Exception connectHybridWithTokenFallback(String host, int port,
                                                      CredentialProvider credentials)
            throws Exception {
        CredSspTokenMode primaryMode = Boolean.getBoolean("tomata.rdp.preferSpnego")
                ? CredSspTokenMode.SPNEGO_NTLM
                : CredSspTokenMode.RAW_NTLM;
        CredSspTokenMode fallbackMode = primaryMode == CredSspTokenMode.RAW_NTLM
                ? CredSspTokenMode.SPNEGO_NTLM
                : CredSspTokenMode.RAW_NTLM;
        try {
            connectHybridAttempt(host, port, credentials, primaryMode);
            return null;
        } catch (Exception primaryFailure) {
            if (!isCredSspTokenFormatFailure(primaryFailure)) {
                throw primaryFailure;
            }
            logger.log(Level.WARNING,
                    "HYBRID " + primaryMode + "被服务器拒绝，使用全新连接回退到"
                            + fallbackMode + ": " + primaryFailure.getMessage());
            try {
                connectHybridAttempt(host, port, credentials, fallbackMode);
                return primaryFailure;
            } catch (Exception fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    /**
     * 创建一套全新的HYBRID协议对象并执行一次连接。NTLM的RC4状态和消息序号
     * 不能跨尝试复用，所以令牌格式回退必须走这里重新初始化整条连接。
     */
    private void connectHybridAttempt(String host, int port, CredentialProvider dcp,
                                      CredSspTokenMode tokenMode) throws Exception {
        if (rdpLayer != null) {
            try { rdpLayer.disconnect(); } catch (Exception ignored) {}
        }

        options.setCredSspTokenMode(tokenMode);
        options.setRdstlsCredentials(null);
        options.getSecurityTypes().clear();
        options.getSecurityTypes().add(SecurityType.STANDARD);
        options.getSecurityTypes().add(SecurityType.HYBRID);

        RdpState rdpState = new RdpState(options);
        rdpState.lockRdp5();
        state = rdpState;

        long attemptId = beginAttempt();
        EmbeddedContext context = new EmbeddedContext(attemptId, state);
        canvas = new RdesktopCanvas(context, state);
        context.bindCanvas(canvas);
        state.setCanvas(canvas);

        VChannels channels = new FixedVChannels(state);
        registerRdpdrChannel(channels);
        registerClipboardChannel(state, canvas, channels);
        registerSoundChannel(channels);
        registerGraphicsChannel(channels);
        rdpLayer = new RdpPatch(context, state, channels);
        configureFrameCallback((RdpPatch) rdpLayer, attemptId);

        RdpTlsFix.injectRdpTransport(rdpLayer, host);
        RdpIsoFix.injectRdpIso(rdpLayer);

        logger.info("回退重连: " + host + ":" + port
                + " securityType=HYBRID (CredSSP/NLA), tokenFormat=" + tokenMode);
        rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                dcp, options.getCommand(), options.getDirectory());
    }

    /** Creates a fresh redirected connection and performs native RDSTLS v1. */
    private void connectRdstlsAttempt(String host, int port,
                                      RdstlsCredentials credentials) throws Exception {
        if (rdpLayer != null) {
            try { rdpLayer.disconnect(); } catch (Exception ignored) {}
        }

        options.setRdstlsCredentials(credentials);
        options.getSecurityTypes().clear();
        options.getSecurityTypes().add(SecurityType.STANDARD);
        // Match the RDSTLS negotiation defined by MS-RDPBCGR/FreeRDP: offer
        // PROTOCOL_SSL as the compatible fallback and prefer PROTOCOL_RDSTLS.
        // The redirected opaque password is still sent in TS_INFO_PACKET when
        // the destination selects SSL.
        options.getSecurityTypes().add(SecurityType.SSL);
        options.getSecurityTypes().add(SecurityType.RDSTLS);

        RdpState rdpState = new RdpState(options);
        rdpState.lockRdp5();
        state = rdpState;

        long attemptId = beginAttempt();
        EmbeddedContext context = new EmbeddedContext(attemptId, state);
        canvas = new RdesktopCanvas(context, state);
        context.bindCanvas(canvas);
        state.setCanvas(canvas);

        VChannels channels = new FixedVChannels(state);
        registerRdpdrChannel(channels);
        registerClipboardChannel(state, canvas, channels);
        registerSoundChannel(channels);
        registerGraphicsChannel(channels);
        rdpLayer = new RdpPatch(context, state, channels);
        configureFrameCallback((RdpPatch) rdpLayer, attemptId);

        RdpTlsFix.injectRdpTransport(rdpLayer, host);
        RdpIsoFix.injectRdpIso(rdpLayer);

        DefaultCredentialsProvider redirectedIdentity = new DefaultCredentialsProvider(
                credentials.getDomain(), credentials.getUsername(), new char[0]);
        logger.info("重定向连接: " + host + ":" + port
                + " securityType=RDSTLS, account="
                + (credentials.getDomain().isBlank()
                        ? credentials.getUsername()
                        : credentials.getDomain() + "\\" + credentials.getUsername()));
        rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                redirectedIdentity, options.getCommand(), options.getDirectory());
    }

    /** 只对令牌格式/封装不兼容进行第二次认证，避免密码错误时重复尝试。 */
    private boolean isCredSspTokenFormatFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof HResultException hresult
                    && hresult.getFacility() == 9 && hresult.getCode() == 0x0308) {
                return true; // SEC_E_INVALID_TOKEN
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("internal_error")
                        || normalized.contains("internal error")
                        || normalized.contains("invalid token")
                        || normalized.contains("unsupported spnego token")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 将CredSSP服务端错误翻译为可操作的信息。WinPR/FreeRDP在NTLM服务端
     * 校验失败时可能把线程中遗留的ERROR_MORE_DATA (234)放入TSRequest，
     * 它不是SPNEGO继续交换指令，不能据此重复认证。
     */
    private String describeHybridFailure(Throwable error, CredentialProvider credentials) {
        HResultException hresult = findCause(error, HResultException.class);
        if (hresult != null && hresult.getFacility() == 7 && hresult.getCode() == 0x00ea) {
            String domain = "";
            String username = "";
            try {
                java.util.List<String> identity = credentials.getCredentials("nla-diagnostic", 0,
                        CredentialProvider.CredentialType.DOMAIN,
                        CredentialProvider.CredentialType.USERNAME);
                if (identity != null) {
                    domain = identity.size() > 0 && identity.get(0) != null ? identity.get(0) : "";
                    username = identity.size() > 1 && identity.get(1) != null ? identity.get(1) : "";
                }
            } catch (RuntimeException ignored) {
                // 诊断文本不能覆盖原始连接异常。
            }
            String account = domain.isBlank() ? username : domain + "\\" + username;
            if (account.isBlank()) {
                account = "(空)";
            }
            return "NLA身份验证被服务端拒绝（Win32 234，当前用户=" + account
                    + "）。GNOME/FreeRDP通常在RDP凭据未配置、凭据库不可用，"
                    + "或用户名/密码与‘远程登录’凭据不一致时返回此码；"
                    + "它不会自动使用Linux系统登录密码。原始错误: " + hresult.getMessage();
        }
        return describeException(error);
    }

    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /** 将无原因码的断开补充为服务端关闭，并保留底层网络异常。 */
    private String describeDisconnect(RdesktopDisconnectException error) {
        if (error.getReason() == RdesktopDisconnectException.exDiscReasonAPIInitiatedDisconnect) {
            return "服务端在NLA认证和RDP激活完成后主动终止桌面会话（错误码0x0001）；"
                    + "本次RDP凭据已通过验证，与SSH账号/密码无关。若服务端为Ubuntu 22/GNOME，"
                    + "请检查图形桌面是否已登录且未锁屏、是否已有远程会话，并查看图形桌面用户的"
                    + "gnome-remote-desktop日志";
        }
        if (error.getReason() == RdesktopDisconnectException.exDiscReasonNoInfo) {
            Throwable cause = error.getCause();
            if (connected) {
                return "服务端在NLA认证和RDP激活完成后关闭桌面会话；本次RDP凭据已通过验证，"
                        + "与SSH账号/密码无关。若服务端为Ubuntu 22/GNOME，请检查图形桌面是否已登录"
                        + "且未锁屏、是否已有远程会话，并查看图形桌面用户的gnome-remote-desktop日志"
                        + (cause == null ? "" : "（底层异常: " + describeException(cause) + "）");
            }
            return cause == null
                    ? "服务端关闭了RDP连接"
                    : "服务端关闭了RDP连接（" + describeException(cause) + "）";
        }
        return describeException(error);
    }

    /** 返回可复制、不会退化成null的异常摘要，并保留最底层异常类型。 */
    private String describeException(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable root = error;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 12; depth++) {
            root = root.getCause();
        }
        String rootMessage = root.getMessage();
        String rootDescription = root.getClass().getSimpleName()
                + (rootMessage == null || rootMessage.isBlank() ? "" : ": " + rootMessage);
        String message = error.getMessage();
        if (error == root || message == null || message.isBlank()
                || message.equals(rootMessage)) {
            return rootDescription;
        }
        return message + "（根因: " + rootDescription + "）";
    }

    /**
     * 查询连接状态
     */
    public boolean isConnected() {
        return connected && rdpLayer != null && rdpLayer.isConnected();
    }

    /** SwingNode跨窗口前主动复位远端修饰键，弥补偶发缺失的AWT失焦事件。 */
    public void releaseRemoteModifierKeys() {
        RdesktopCanvas currentCanvas = canvas;
        if (currentCanvas != null) {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                currentCanvas.lostFocus();
            } else {
                javax.swing.SwingUtilities.invokeLater(currentCanvas::lostFocus);
            }
        }
    }

    /**
     * 设置断开连接回调
     */
    public void setOnDisconnected(Consumer<String> callback) {
        this.onDisconnected = callback;
    }

    /**
     * 设置连接就绪回调
     */
    public void setOnConnected(Consumer<Void> callback) {
        this.onConnected = callback;
    }

    /** Sets a callback that runs only after the first desktop bitmap is rendered. */
    public void setOnFirstFrame(Consumer<Void> callback) {
        this.onFirstFrame = callback;
    }

    private void configureFrameCallback(RdpPatch patch, long attemptId) {
        patch.setOnFirstFrame(v -> {
            if (attemptId != currentAttemptId) {
                return;
            }
            Consumer<Void> callback = onFirstFrame;
            if (callback != null) {
                callback.accept(null);
            }
        });
    }

    /**
     * 获取渲染画布的JComponent（仅在onConnected回调后才有效）
     */
    public JComponent getDisplayComponent() {
        return canvas != null ? (JComponent) canvas.getDisplay() : null;
    }

    /**
     * 获取RdesktopCanvas
     */
    public RdesktopCanvas getCanvas() {
        return canvas;
    }
}
