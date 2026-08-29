package com.tangluobo.rdp4j;

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;

import com.tangluobo.rdp4j.graphics.RdpCursor;
import com.tangluobo.rdp4j.graphics.WrappedImage;

import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * RDP远程桌面JavaFX容器组件
 * 通过SwingNode嵌入sshtools RDP库的Swing渲染组件
 */
public class RdpPane extends BorderPane {

    private static final Logger logger = Logger.getLogger(RdpPane.class.getName());

    private RdpClient rdpClient;
    private SwingNode swingNode;
    private volatile JScrollPane desktopScrollPane;
    private volatile JComponent desktopDisplay;
    private volatile JComponent displayBeingAttached;
    private volatile boolean windowScrollBarsSuppressed;
    private boolean directKeyboardBridgeLogged;
    private final WindowsImeController windowsImeController = new WindowsImeController();
    private final Set<javafx.scene.input.KeyCode> locallyConsumedFxKeys =
            EnumSet.noneOf(javafx.scene.input.KeyCode.class);
    private int verticalPolicyBeforeFullScreen = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED;
    private int horizontalPolicyBeforeFullScreen = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED;

    // 状态栏组件
    private HBox statusBar;
    private Circle statusDot;
    private TextField stateTextField;
    private Label connLabel;
    private Label resolutionLabel;

    // 全屏支持
    private Tab ownerTab;                 // 所属tab（全屏还原时放回）
    private Stage fullScreenStage;        // 非null表示当前处于全屏
    private StackPane fullScreenRoot;
    private HBox exitBar;                 // 顶部悬浮"退出全屏"按钮（mstsc风格）
    private PauseTransition hideExitBarTimer;
    private TranslateTransition exitBarSlide;
    private boolean exitBarPinned;
    private java.awt.event.AWTEventListener fullScreenEdgeMouseListener;
    private java.awt.KeyEventDispatcher fullScreenKeyDispatcher; // 常驻拦截Ctrl+Shift+Enter切换全屏（Swing焦点场景，校验焦点在RDP画布）
    private boolean fullScreenTransitioning; // 防止fullScreen/close监听器重入，造成视图留在已关闭的Scene中
    private long sceneRefreshGeneration;     // 丢弃快速连续切换产生的过期刷新任务
    private PauseTransition viewportRefreshTimer; // 窗口缩放结束后补一次整幅同步刷新

    // 连接信息
    private String host;
    private int port;
    private String username;
    private String password;
    private String domain;
    private int screenWidth;
    private int screenHeight;
    private int colorDepth;

    /** 全屏切换快捷键（默认Ctrl+Shift+回车，可通过 setFullScreenShortcut 动态修改） */
    private volatile KeyCombination fullScreenKeys = KeyCombination.valueOf("Ctrl+Shift+Enter");

    public RdpPane() {
        rdpClient = new RdpClient();
        initializeUI();
    }

    private void initializeUI() {
        // 中心：SwingNode嵌入RDP渲染
        swingNode = new SwingNode();
        // SwingNode refuses to create an AWT KeyEvent when JavaFX reports an
        // empty character string. Windows does exactly that for physical keys
        // while a local Chinese IME is active, so intercept the FX event first
        // and send its key code through the normal RDP input pipeline.
        swingNode.addEventFilter(javafx.scene.input.KeyEvent.ANY, this::forwardFxKeyboardEvent);
        swingNode.addEventFilter(javafx.scene.input.InputMethodEvent.ANY, event -> {
            if (desktopDisplay != null && rdpClient.isConnected()) {
                event.consume();
            }
        });
        swingNode.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) {
                windowsImeController.disableForFocusedWindow();
            } else {
                windowsImeController.restore();
            }
        });
        setCenter(swingNode);
        // SwingNode does not reliably propagate JavaFX layout changes to a nested
        // JScrollPane. Keep the Swing viewport in sync so resize exposes/repaints
        // the newly visible desktop area immediately.
        swingNode.layoutBoundsProperty().addListener((obs, oldValue, newValue) -> resizeDesktopViewport());

        // 全屏切换快捷键（FX焦点场景）：跟随所在Scene自动注册/注销加速键，
        // tab内和全屏窗口内均生效；全屏进入/退出引起Scene变化时同样自动迁移
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.getAccelerators().remove(fullScreenKeys);
            }
            if (newScene != null) {
                newScene.getAccelerators().put(fullScreenKeys, this::toggleFullScreen);
            }
        });

        // 全屏切换快捷键（Swing焦点场景）：焦点在RDP画布时按键进入AWT而非FX，
        // 通过全局键分发器拦截并消费，避免被转发到远程桌面。常驻注册但校验焦点归属。
        fullScreenKeyDispatcher = e -> {
            if (matchesFullScreenKeys(e)) {
                java.awt.Component focusOwner = java.awt.KeyboardFocusManager
                        .getCurrentKeyboardFocusManager().getFocusOwner();
                JComponent display = desktopDisplay;
                if (display != null && focusOwner != null
                        && (focusOwner == display || SwingUtilities.isDescendingFrom(focusOwner, display))) {
                    // 修饰键按下事件已发往远端；跨Scene后释放事件可能落不到旧组件。
                    rdpClient.releaseRemoteModifierKeys();
                    Platform.runLater(this::toggleFullScreen);
                    return true; // 消费该事件
                }
            }
            return false;
        };
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(fullScreenKeyDispatcher);

        // 底部：状态栏
        statusBar = createStatusBar();
        setBottom(statusBar);

        // 设置样式
        getStyleClass().add("rdp-pane");
    }

    private void forwardFxKeyboardEvent(javafx.scene.input.KeyEvent event) {
        if (desktopDisplay == null || rdpClient == null || !rdpClient.isConnected()) {
            return;
        }

        if (event.getEventType() == javafx.scene.input.KeyEvent.KEY_TYPED) {
            // Press/release events are authoritative. Consuming KEY_TYPED also
            // prevents locally committed IME text from entering the RDP path.
            event.consume();
            return;
        }

        javafx.scene.input.KeyCode code = event.getCode();
        if (event.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED
                && locallyConsumedFxKeys.remove(code)) {
            event.consume();
            return;
        }
        if (event.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED
                && fullScreenKeys.match(event)) {
            locallyConsumedFxKeys.add(code);
            event.consume();
            rdpClient.releaseRemoteModifierKeys();
            toggleFullScreen();
            return;
        }

        int awtId = event.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED
                ? java.awt.event.KeyEvent.KEY_PRESSED : java.awt.event.KeyEvent.KEY_RELEASED;
        int awtKeyCode = code.getCode();
        if (awtKeyCode == java.awt.event.KeyEvent.VK_UNDEFINED) {
            logger.fine("忽略无法映射的JavaFX按键: " + code);
            event.consume();
            return;
        }
        if (rdpClient.forwardKeyboardEvent(
                awtId, toAwtModifiers(event), awtKeyCode, toAwtKeyLocation(code))) {
            if (!directKeyboardBridgeLogged) {
                directKeyboardBridgeLogged = true;
                logger.info("RDP JavaFX键盘桥已启用: firstKey=" + code
                        + ", emptyCharacter=" + event.getCharacter().isEmpty());
            }
            // Do not let SwingNode forward the same event a second time when
            // the local input method happens to be in English mode.
            event.consume();
        }
    }

    static int toAwtModifiers(javafx.scene.input.KeyEvent event) {
        int modifiers = 0;
        if (event.isShiftDown()) modifiers |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        if (event.isControlDown()) modifiers |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
        if (event.isAltDown()) modifiers |= java.awt.event.InputEvent.ALT_DOWN_MASK;
        if (event.isMetaDown()) modifiers |= java.awt.event.InputEvent.META_DOWN_MASK;
        return modifiers;
    }

    static int toAwtKeyLocation(javafx.scene.input.KeyCode code) {
        return switch (code) {
            case NUMPAD0, NUMPAD1, NUMPAD2, NUMPAD3, NUMPAD4,
                    NUMPAD5, NUMPAD6, NUMPAD7, NUMPAD8, NUMPAD9,
                    MULTIPLY, ADD, SEPARATOR, SUBTRACT, DECIMAL, DIVIDE,
                    KP_UP, KP_DOWN, KP_LEFT, KP_RIGHT ->
                    java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD;
            default -> java.awt.event.KeyEvent.KEY_LOCATION_STANDARD;
        };
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        // 状态指示灯
        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        bar.getChildren().add(statusDot);

        // 使用只读文本框承载状态：长错误不会撑开状态栏，同时可以选择并复制。
        stateTextField = new TextField("未连接");
        stateTextField.setEditable(false);
        stateTextField.setFocusTraversable(true);
        stateTextField.setMinWidth(80);
        stateTextField.setPrefWidth(220);
        stateTextField.setMaxWidth(360);
        stateTextField.setStyle("-fx-font-size: 11px; -fx-background-color: transparent; "
                + "-fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");
        Tooltip fullStatus = new Tooltip();
        fullStatus.textProperty().bind(stateTextField.textProperty());
        fullStatus.setWrapText(true);
        fullStatus.setMaxWidth(600);
        stateTextField.setTooltip(fullStatus);
        bar.getChildren().add(stateTextField);

        // 分隔
        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        bar.getChildren().add(sep1);

        // 连接信息
        connLabel = new Label("");
        connLabel.setStyle("-fx-font-size: 11px;");
        bar.getChildren().add(connLabel);

        // 弹性空间
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().add(spacer);

        // 分辨率标签
        resolutionLabel = new Label("");
        resolutionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        bar.getChildren().add(resolutionLabel);

        return bar;
    }

    /**
     * 连接到RDP服务器
     *
     * @param mapClipboard 是否启用剪贴板同步（本地与远程桌面互拷文本）
     * @param enableSound  是否启用远程音频重定向（远程桌面声音在本地播放）
     */
    public void connect(String host, int port, String username, String password,
                        String domain, int screenWidth, int screenHeight, int colorDepth,
                        boolean useSsl, boolean mapClipboard, boolean enableSound) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.colorDepth = colorDepth;
        desktopDisplay = null;
        desktopScrollPane = null;
        displayBeingAttached = null;

        // 更新状态栏
        updateStatus(ConnectionState.CONNECTING);
        connLabel.setText(username + "@" + host + ":" + port);
        resolutionLabel.setText(screenWidth + "x" + screenHeight + " @" + colorDepth);

        // 先显示加载占位面板（Swing组件在EDT创建，SwingNode.setContent在JavaFX线程）
        SwingUtilities.invokeLater(() -> {
            JPanel loadingPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
            loadingPanel.setBackground(new java.awt.Color(32, 32, 32));
            JLabel loadingLabel = new JLabel("正在连接到 " + host + " ...");
            loadingLabel.setFont(loadingLabel.getFont().deriveFont(java.awt.Font.PLAIN, 14));
            loadingLabel.setForeground(new java.awt.Color(230, 230, 230));
            loadingPanel.add(loadingLabel);
            Platform.runLater(() -> swingNode.setContent(loadingPanel));
        });

        // DISPLAY ready只表示协议和绘图通道可用。此时画布仍可能是0x0或
        // 全黑，而且服务端可能马上要求会话重定向；不要提前替换加载页。
        rdpClient.setOnConnected(v -> {
            final JComponent displayComponent = rdpClient.getDisplayComponent();
            if (displayComponent == null) {
                logger.warning("RDP显示组件为null，无法显示");
                Platform.runLater(() -> updateStatus(ConnectionState.ERROR));
                return;
            }
            logger.info("RDP显示通道已就绪，等待首帧: " + displayComponent.getClass().getSimpleName()
                    + " size=" + displayComponent.getSize()
                    + " prefSize=" + displayComponent.getPreferredSize());
        });

        // 设置断开回调
        rdpClient.setOnDisconnected(reason -> {
            Platform.runLater(() -> {
                updateStatus(ConnectionState.DISCONNECTED);
                stateTextField.setText("已断开: " + reason);
            });
        });

        rdpClient.setOnFirstFrame(v -> attachDesktopAfterFirstFrame());

        // 在EDT中初始化RDP连接（画布不在SwingNode中显示，直到onConnected回调）
        SwingUtilities.invokeLater(() -> {
            try {
                rdpClient.connect(host, port, username, password, domain,
                        screenWidth, screenHeight, colorDepth, useSsl, mapClipboard, enableSound);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "RDP连接失败: " + e.getMessage(), e);
                Platform.runLater(() -> {
                    updateStatus(ConnectionState.ERROR);
                    stateTextField.setText("连接失败: " + e.getMessage());
                });
            }
        });
    }

    /** Keeps the stable loading surface visible until the current attempt has pixels to show. */
    private void attachDesktopAfterFirstFrame() {
        final JComponent displayComponent = rdpClient.getDisplayComponent();
        if (displayComponent == null) {
            logger.warning("RDP已收到首帧，但显示组件为null");
            return;
        }
        synchronized (this) {
            if (displayComponent == desktopDisplay || displayComponent == displayBeingAttached) {
                return;
            }
            displayBeingAttached = displayComponent;
        }

		// SwingNode maps AWT custom cursors to Cursor.DEFAULT. RDP pointer
		// shapes (including window-edge resize arrows) are all custom images,
		// so pass their source image directly to JavaFX instead.
		if (displayComponent instanceof WrappedImage wrappedImage) {
			wrappedImage.setRdpCursorListener(this::updateFxCursor);
		}

        SwingUtilities.invokeLater(() -> {
            if (!isCurrentDisplay(displayComponent)) {
                clearDisplayBeingAttached(displayComponent);
                return;
            }
            displayComponent.setSize(displayComponent.getPreferredSize());
            // 用GridBag居中宿主承载远程画布：窗口大于远程分辨率时画面保持
            // 水平、垂直居中；窗口缩小时宿主仍以画布首选尺寸参与滚动计算。
            JPanel canvasHost = new JPanel(new GridBagLayout());
            canvasHost.setBackground(java.awt.Color.BLACK);
            canvasHost.add(displayComponent);
            JScrollPane scrollPane = new JScrollPane(canvasHost);
            scrollPane.setBackground(java.awt.Color.BLACK);
            scrollPane.getViewport().setBackground(java.awt.Color.BLACK);
            scrollPane.setBorder(null);
            scrollPane.getViewport().setBorder(null);
            applyWindows10ScrollBars(scrollPane);
            if (windowScrollBarsSuppressed) {
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            }
            scrollPane.setDoubleBuffered(true);

            Platform.runLater(() -> {
                if (!isCurrentDisplay(displayComponent)) {
                    clearDisplayBeingAttached(displayComponent);
                    return;
                }
                desktopDisplay = displayComponent;
                desktopScrollPane = scrollPane;
                clearDisplayBeingAttached(displayComponent);
                swingNode.setContent(scrollPane);
                resizeDesktopViewport();
                updateStatus(ConnectionState.CONNECTED);
                logger.info("RDP首帧已就绪，显示远程桌面");
                SwingUtilities.invokeLater(() -> {
                    displayComponent.revalidate();
                    displayComponent.repaint();
                    displayComponent.requestFocusInWindow();
                });
            });
        });
    }

    private boolean isCurrentDisplay(JComponent displayComponent) {
        return rdpClient.isConnected() && rdpClient.getDisplayComponent() == displayComponent;
    }

    private synchronized void clearDisplayBeingAttached(JComponent displayComponent) {
        if (displayBeingAttached == displayComponent) {
            displayBeingAttached = null;
        }
    }

	private void updateFxCursor(RdpCursor rdpCursor) {
		Platform.runLater(() -> applyFxCursor(rdpCursor));
	}

	private void applyFxCursor(RdpCursor rdpCursor) {
		if (rdpCursor == null || rdpCursor.getData() == null) {
			swingNode.setCursor(Cursor.DEFAULT);
			return;
		}

		java.awt.Image awtImage = rdpCursor.getData();
		int width = awtImage.getWidth(null);
		int height = awtImage.getHeight(null);
		if (width <= 0 || height <= 0) {
			return;
		}
		Dimension2D bestSize = ImageCursor.getBestSize(width, height);
		int targetWidth = bestSize.getWidth() > 0 ? (int) Math.round(bestSize.getWidth()) : width;
		int targetHeight = bestSize.getHeight() > 0 ? (int) Math.round(bestSize.getHeight()) : height;
		WritableImage fxImage = createFxCursorImage(awtImage, targetWidth, targetHeight);
		double hotspotX = Math.max(0, Math.min(
				rdpCursor.getHotspot().x * (double) targetWidth / width, targetWidth - 1));
		double hotspotY = Math.max(0, Math.min(
				rdpCursor.getHotspot().y * (double) targetHeight / height, targetHeight - 1));
		swingNode.setCursor(new ImageCursor(fxImage, hotspotX, hotspotY));
	}

	static WritableImage createFxCursorImage(java.awt.Image awtImage, int width, int height) {
		int sourceWidth = awtImage.getWidth(null);
		int sourceHeight = awtImage.getHeight(null);
		if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Cursor dimensions must be positive");
		}
		BufferedImage source = new BufferedImage(sourceWidth, sourceHeight,
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = source.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(awtImage, 0, 0, null);
		} finally {
			graphics.dispose();
		}

		// Windows reports a 48x48 native cursor on a 150% display while many RDP
		// servers still transmit a 32x32 pointer. Nearest-neighbour 32->48 scaling
		// makes vertical edges look sharp but gives diagonals uneven 1/2-pixel
		// steps. Resample straight ARGB as premultiplied color with Lanczos3, then
		// hand the premultiplied integers directly to Glass. This smooths diagonal
		// coverage without introducing dark fringes around transparent pixels.
		int[] pixels = resampleCursorArgbPre(source, width, height);
		PixelBuffer<IntBuffer> pixelBuffer = new PixelBuffer<>(width, height,
				IntBuffer.wrap(pixels), PixelFormat.getIntArgbPreInstance());
		return new WritableImage(pixelBuffer);
	}

	private static int[] resampleCursorArgbPre(BufferedImage source, int targetWidth,
			int targetHeight) {
		int sourceWidth = source.getWidth();
		int sourceHeight = source.getHeight();
		int[] sourcePixels = source.getRGB(0, 0, sourceWidth, sourceHeight,
				null, 0, sourceWidth);
		double[] horizontal = new double[sourceHeight * targetWidth * 4];
		double scaleX = sourceWidth / (double) targetWidth;
		double filterScaleX = Math.max(1.0, scaleX);
		double radiusX = 3.0 * filterScaleX;

		for (int y = 0; y < sourceHeight; y++) {
			for (int targetX = 0; targetX < targetWidth; targetX++) {
				double sourceX = (targetX + 0.5) * scaleX - 0.5;
				int first = (int) Math.ceil(sourceX - radiusX);
				int last = (int) Math.floor(sourceX + radiusX);
				double weightSum = 0;
				double alpha = 0;
				double red = 0;
				double green = 0;
				double blue = 0;
				for (int sampleX = first; sampleX <= last; sampleX++) {
					double weight = lanczos3((sourceX - sampleX) / filterScaleX);
					if (weight == 0) continue;
					int clampedX = Math.max(0, Math.min(sourceWidth - 1, sampleX));
					int argb = sourcePixels[y * sourceWidth + clampedX];
					double sampleAlpha = ((argb >>> 24) & 0xff) / 255.0;
					weightSum += weight;
					alpha += weight * sampleAlpha;
					red += weight * ((argb >>> 16) & 0xff) / 255.0 * sampleAlpha;
					green += weight * ((argb >>> 8) & 0xff) / 255.0 * sampleAlpha;
					blue += weight * (argb & 0xff) / 255.0 * sampleAlpha;
				}
				int offset = (y * targetWidth + targetX) * 4;
				horizontal[offset] = alpha / weightSum;
				horizontal[offset + 1] = red / weightSum;
				horizontal[offset + 2] = green / weightSum;
				horizontal[offset + 3] = blue / weightSum;
			}
		}

		int[] result = new int[targetWidth * targetHeight];
		double scaleY = sourceHeight / (double) targetHeight;
		double filterScaleY = Math.max(1.0, scaleY);
		double radiusY = 3.0 * filterScaleY;
		for (int targetY = 0; targetY < targetHeight; targetY++) {
			double sourceY = (targetY + 0.5) * scaleY - 0.5;
			int first = (int) Math.ceil(sourceY - radiusY);
			int last = (int) Math.floor(sourceY + radiusY);
			for (int x = 0; x < targetWidth; x++) {
				double weightSum = 0;
				double alpha = 0;
				double red = 0;
				double green = 0;
				double blue = 0;
				for (int sampleY = first; sampleY <= last; sampleY++) {
					double weight = lanczos3((sourceY - sampleY) / filterScaleY);
					if (weight == 0) continue;
					int clampedY = Math.max(0, Math.min(sourceHeight - 1, sampleY));
					int offset = (clampedY * targetWidth + x) * 4;
					weightSum += weight;
					alpha += weight * horizontal[offset];
					red += weight * horizontal[offset + 1];
					green += weight * horizontal[offset + 2];
					blue += weight * horizontal[offset + 3];
				}
				double premultipliedAlpha = clamp01(alpha / weightSum);
				int a = toByte(premultipliedAlpha);
				int r = toByte(Math.min(premultipliedAlpha,
						Math.max(0, red / weightSum)));
				int g = toByte(Math.min(premultipliedAlpha,
						Math.max(0, green / weightSum)));
				int b = toByte(Math.min(premultipliedAlpha,
						Math.max(0, blue / weightSum)));
				result[targetY * targetWidth + x] = (a << 24) | (r << 16) | (g << 8) | b;
			}
		}
		return result;
	}

	private static double lanczos3(double value) {
		double x = Math.abs(value);
		if (x < 1.0e-9) return 1;
		if (x >= 3) return 0;
		double piX = Math.PI * x;
		return (Math.sin(piX) / piX) * (Math.sin(piX / 3) / (piX / 3));
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	private static int toByte(double value) {
		return (int) Math.round(clamp01(value) * 255);
	}

    /**
     * 断开连接
     */
    public void disconnect() {
        windowsImeController.restore();
        displayBeingAttached = null;
        // 断开前先退出全屏，把组件还原到tab中，避免全屏窗口残留
        if (fullScreenStage != null) {
            exitFullScreen();
        }
        // 进入全屏过程中若窗口初始化异常，也确保旁听器不会残留在AWT Toolkit上。
        stopFullScreenEdgeMonitor();
        // 注销常驻的全屏切换键分发器（组件销毁，避免泄漏）
        if (fullScreenKeyDispatcher != null) {
            try {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removeKeyEventDispatcher(fullScreenKeyDispatcher);
            } catch (Exception ignored) {
            }
            fullScreenKeyDispatcher = null;
        }
        // 从所在Scene注销全屏切换加速键
        if (getScene() != null) {
            getScene().getAccelerators().remove(fullScreenKeys);
        }
        if (rdpClient != null) {
            rdpClient.disconnect();
        }
        SwingUtilities.invokeLater(() -> swingNode.setContent(null));
        desktopScrollPane = null;
        desktopDisplay = null;
        updateStatus(ConnectionState.DISCONNECTED);
    }

    /**
     * 查询连接状态
     */
    public boolean isConnected() {
        return rdpClient != null && rdpClient.isConnected();
    }

    /**
     * 请求焦点（确保键盘事件正确路由到RDP画布）
     */
    public void requestRdpFocus() {
        if (swingNode != null) {
            swingNode.requestFocus();
            // requestFocus() may be called while the SwingNode is already the
            // JavaFX focus owner, in which case focusedProperty does not fire.
            windowsImeController.disableForFocusedWindow();
        }
        JComponent display = desktopDisplay;
        if (display != null) {
            SwingUtilities.invokeLater(display::requestFocusInWindow);
        }
    }

    // ==================== 全屏支持 ====================

    /**
     * 设置所属tab（全屏还原时需要把组件放回tab）
     */
    public void setOwnerTab(Tab ownerTab) {
        this.ownerTab = ownerTab;
    }

    public boolean isFullScreen() {
        return fullScreenStage != null;
    }

    /**
     * 设置全屏切换快捷键（如 "Ctrl+Alt+Enter"），非法组合保持不变。
     * 同步更新已注册的Scene加速键与全屏窗口的退出键。
     */
    public void setFullScreenShortcut(String shortcutText) {
        String text = (shortcutText == null || shortcutText.isBlank())
                ? "Ctrl+Shift+Enter" : shortcutText.trim();
        KeyCombination newKeys;
        try {
            newKeys = KeyCombination.valueOf(text);
        } catch (IllegalArgumentException e) {
            return;
        }
        KeyCombination oldKeys = fullScreenKeys;
        fullScreenKeys = newKeys;
        // 重新注册当前Scene上的加速键
        Scene scene = getScene();
        if (scene != null) {
            if (oldKeys != null) {
                scene.getAccelerators().remove(oldKeys);
            }
            scene.getAccelerators().put(newKeys, this::toggleFullScreen);
        }
        // 全屏中则同步更新全屏窗口的退出键
        Stage stage = fullScreenStage;
        if (stage != null) {
            stage.setFullScreenExitKeyCombination(newKeys);
            stage.setFullScreenExitHint("按 " + newKeys.getName() + " 退出全屏");
        }
    }

    /** 当前生效的全屏切换快捷键显示文本（用于菜单展示） */
    public String getFullScreenShortcutText() {
        KeyCombination keys = fullScreenKeys;
        return keys != null ? keys.getName() : "Ctrl+Shift+Enter";
    }

    /**
     * 判断AWT按键事件是否命中当前全屏切换快捷键。
     * 将AWT事件转换为JavaFX KeyEvent后交给KeyCombination.match统一匹配，
     * 保证快捷键修改后FX加速键与AWT拦截行为一致。
     */
    private boolean matchesFullScreenKeys(java.awt.event.KeyEvent e) {
        if (e.getID() != java.awt.event.KeyEvent.KEY_PRESSED) {
            return false;
        }
        KeyCombination keys = fullScreenKeys;
        if (keys == null) {
            return false;
        }
        try {
            String keyText = java.awt.event.KeyEvent.getKeyText(e.getKeyCode());
            javafx.scene.input.KeyCode code = javafx.scene.input.KeyCode.valueOf(
                    keyText.replace(" ", "_").toUpperCase());
            javafx.scene.input.KeyEvent fxEvent = new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code,
                    e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
            return keys.match(fxEvent);
        } catch (IllegalArgumentException ex) {
            return false; // 无法映射的按键不处理
        }
    }

    /**
     * 切换全屏/窗口模式
     */
    public void toggleFullScreen() {
        if (fullScreenTransitioning) {
            return;
        }
        if (fullScreenStage != null) {
            exitFullScreen();
        } else {
            enterFullScreen();
        }
    }

    /**
     * 进入全屏：把RDP视图从tab移到独立全屏窗口，隐藏状态栏。
     * 鼠标靠近屏幕顶部边沿时滑出"退出全屏"悬浮按钮（mstsc风格）。
     */
    private void enterFullScreen() {
        if (ownerTab == null || fullScreenStage != null || fullScreenTransitioning) {
            return;
        }
        rdpClient.releaseRemoteModifierKeys();
        fullScreenTransitioning = true;
        JScrollPane currentScrollPane = desktopScrollPane;
        if (currentScrollPane != null) {
            verticalPolicyBeforeFullScreen = currentScrollPane.getVerticalScrollBarPolicy();
            horizontalPolicyBeforeFullScreen = currentScrollPane.getHorizontalScrollBarPolicy();
        }
        // tab内容用占位面板顶替，保持tab结构不变
        ownerTab.setContent(new StackPane());
        // 全屏时隐藏状态栏，只显示远程桌面
        setBottom(null);
        // 全屏期间背景全黑：缩放取整产生的边缘缝隙显示为黑色而不是浅色底
        setStyle("-fx-background-color: black;");

        // 顶部悬浮控制条。默认不固定：鼠标离开5秒后收起；固定后始终显示。
        exitBarPinned = false;
        exitBar = new HBox(8);
        exitBar.setAlignment(Pos.CENTER);
        exitBar.setPrefWidth(520);
        exitBar.setMinWidth(175);
        // StackPane会把可调整大小的子节点扩展到maxWidth，因此用相同的
        // pref/max值锁定当前宽度；拖动边缘时同时更新二者。
        exitBar.setMaxWidth(520);
        exitBar.setMinHeight(Region.USE_PREF_SIZE);
        exitBar.setMaxHeight(Region.USE_PREF_SIZE);
        exitBar.setStyle("-fx-background-color: linear-gradient(to bottom, #3d8bd2, #07519a);"
                + " -fx-background-radius: 0 0 8 8; -fx-border-color: #75b8f0;"
                + " -fx-border-width: 0 1 1 1; -fx-border-radius: 0 0 8 8;"
                + " -fx-padding: 4 0 4 0;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 7, 0.3, 0, 2);");

        ToggleButton pinBtn = new ToggleButton();
        pinBtn.setFocusTraversable(false);
        pinBtn.setMinWidth(Region.USE_PREF_SIZE);
        pinBtn.setGraphic(createPinIcon());
        pinBtn.setTooltip(createControlTooltip("固定控制条"));
        pinBtn.setStyle(controlBarButtonStyle(false));
        pinBtn.selectedProperty().addListener((obs, oldValue, pinned) -> {
            exitBarPinned = pinned;
            pinBtn.setTooltip(createControlTooltip(pinned ? "取消固定控制条" : "固定控制条"));
            pinBtn.setStyle(controlBarButtonStyle(pinned));
            if (pinned) {
                if (hideExitBarTimer != null) hideExitBarTimer.stop();
                showExitBar();
            } else if (hideExitBarTimer != null) {
                hideExitBarTimer.playFromStart();
            }
        });

        Button qualityBtn = new Button();
        qualityBtn.setFocusTraversable(false);
        qualityBtn.setMinWidth(Region.USE_PREF_SIZE);
        qualityBtn.setGraphic(createSignalIcon());
        qualityBtn.setTooltip(createControlTooltip("查看连接质量"));
        qualityBtn.setStyle(controlBarButtonStyle(false));
        qualityBtn.setOnAction(e -> showConnectionQuality());

        Label titleLabel = new Label(ownerTab.getText() != null ? ownerTab.getText() : "远程桌面");
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.setMinWidth(0);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        Button minimizeBtn = createWindowControlButton(
                "M6 13.5h12v-1H6v1z", "最小化", false);
        minimizeBtn.setOnAction(e -> minimizeFullScreen());

        Button maximizeBtn = createWindowControlButton(
                "M7 3h12v1H7z M18 3h1v12h-1z M7 3h1v3h-1z M16 15h3v1h-3z "
                        + "M4 6h12v12H4V6zm1 1v10h10V7H5z",
                "退出全屏", false);
        maximizeBtn.setOnAction(e -> exitFullScreen());

        Button closeBtn = createWindowControlButton(
                "M19 6.5L18.5 6 12 12.5 5.5 6 5 6.5 11.5 13 5 19.5 5.5 20 12 13.5 18.5 20 19 19.5 12.5 13 19 6.5z",
                "关闭远程桌面", true);
        closeBtn.setOnAction(e -> closeRemoteDesktop());

        Region leftResizeGrip = createControlBarResizeGrip();
        Region rightResizeGrip = createControlBarResizeGrip();
        exitBar.getChildren().addAll(leftResizeGrip, pinBtn, qualityBtn, titleLabel,
                minimizeBtn, maximizeBtn, closeBtn, rightResizeGrip);
        installControlBarAdjustment(titleLabel, leftResizeGrip, rightResizeGrip);
        exitBar.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            double width = newWidth.doubleValue();
            setControlVisible(titleLabel, width >= 380);
            setControlVisible(minimizeBtn, width >= 310);
            setControlVisible(maximizeBtn, width >= 260);
        });
        exitBar.setVisible(true);
        exitBar.setTranslateY(0);
        StackPane.setAlignment(exitBar, Pos.TOP_CENTER);
        // 鼠标悬停在按钮上时保持显示，移开后计时隐藏
        exitBar.setOnMouseEntered(e -> { if (hideExitBarTimer != null) hideExitBarTimer.stop(); });
        exitBar.setOnMouseExited(e -> {
            if (!exitBarPinned && hideExitBarTimer != null) hideExitBarTimer.playFromStart();
        });

        hideExitBarTimer = new PauseTransition(Duration.seconds(5));
        hideExitBarTimer.setOnFinished(e -> hideExitBar());

        fullScreenRoot = new StackPane(this);
        fullScreenRoot.setStyle("-fx-background-color: black;");
        fullScreenRoot.getChildren().add(exitBar);

        // 被SwingNode转成AWT事件的鼠标移动不会稳定到达JavaFX Scene，但不能再用
        // 覆盖式FX感应条兜底：它会截断顶端5px的RDP输入，导致A->B->C套娃时
        // B收不到鼠标到达顶边的事件，C的全屏控制栏也就无法出现。
        // AWT全局观察器只旁听当前RDP滚动区域内的事件，不消费事件，当前层控制栏
        // 能正常出现，同一个移动事件也会继续由RDP输入管线发送给下一层。
        startFullScreenEdgeMonitor();

        Scene scene = new Scene(fullScreenRoot, Color.BLACK);
        // 兜底：鼠标靠近顶部边沿（5px内）时显示退出按钮
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (e.getSceneY() <= 5) {
                showExitBar();
            }
        });
        // Ctrl+Shift+Enter切换全屏的加速键由sceneProperty监听器自动注册到本Scene

        fullScreenStage = new Stage();
        // 覆盖JavaFX默认的Esc退出全屏，改为全屏切换快捷键（与切换键一致）
        fullScreenStage.setFullScreenExitKeyCombination(fullScreenKeys);
        fullScreenStage.setFullScreenExitHint("按 " + fullScreenKeys.getName() + " 退出全屏");
        fullScreenStage.setTitle(ownerTab.getText() != null ? ownerTab.getText() : "远程桌面");
        fullScreenStage.setScene(scene);
        // 全屏窗口定位到主窗口所在屏幕（多显示器时与tab所在屏一致）
        try {
            Scene mainScene = ownerTab.getTabPane().getScene();
            if (mainScene != null && mainScene.getWindow() != null) {
                Window mainWin = mainScene.getWindow();
                Rectangle2D probe = new Rectangle2D(mainWin.getX(), mainWin.getY(), 1, 1);
                for (Screen screen : Screen.getScreensForRectangle(probe)) {
                    fullScreenStage.setX(screen.getBounds().getMinX());
                    fullScreenStage.setY(screen.getBounds().getMinY());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        // 用户按Esc等退出JavaFX全屏状态时，同步还原到tab
        fullScreenStage.fullScreenProperty().addListener((obs, was, is) -> {
            if (was && !is && !fullScreenTransitioning) {
                exitFullScreen();
            }
        });
        fullScreenStage.setOnCloseRequest(e -> {
            e.consume();
            exitFullScreen();
        });
        fullScreenStage.show();
        // Stage必须先可见才能可靠进入全屏；在show()前setFullScreen会被部分JavaFX/Windows
        // 组合忽略，表现为第一次只弹出普通窗口。
        Platform.runLater(() -> {
            Stage stage = fullScreenStage;
            if (stage == null) {
                fullScreenTransitioning = false;
                return;
            }
            stage.setFullScreen(true);
            stage.requestFocus();
            if (fullScreenRoot != null) {
                fullScreenRoot.applyCss();
                fullScreenRoot.layout();
            }
            fullScreenTransitioning = false;
            if (!exitBarPinned && hideExitBarTimer != null) {
                hideExitBarTimer.playFromStart();
            }
            refreshDesktopAfterSceneChange();
        });
    }

    /**
     * 退出全屏：关闭全屏窗口，把RDP视图还原到tab并恢复状态栏
     */
    private void exitFullScreen() {
        Stage stage = fullScreenStage;
        if (stage == null) {
            return;
        }
        rdpClient.releaseRemoteModifierKeys();
        fullScreenTransitioning = true;
        StackPane oldRoot = fullScreenRoot;
        fullScreenStage = null;
        fullScreenRoot = null;
        stopFullScreenEdgeMonitor();
        exitBar = null;
        hideExitBarTimer = null;
        exitBarSlide = null;
        exitBarPinned = false;
        // 恢复缩放（窗口模式1:1显示）
        if (swingNode != null) {
            swingNode.getTransforms().clear();
        }
        // 原样恢复进入全屏前的滚动条策略。首次打开时可能是NEVER，不能无条件
        // 改成AS_NEEDED，否则全屏往返后会凭空出现滚动条。
        final JScrollPane scrollPane = desktopScrollPane;
        if (scrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                scrollPane.setVerticalScrollBarPolicy(verticalPolicyBeforeFullScreen);
                scrollPane.setHorizontalScrollBarPolicy(horizontalPolicyBeforeFullScreen);
            });
        }
        // 恢复状态栏和tab内容
        setBottom(statusBar);
        setStyle(null);
        // 先从旧Scene明确摘除，再放回Tab。否则SwingNode的原生焦点窗口仍可能指向
        // 已关闭的全屏Stage，退出后看得见但鼠标键盘都无法操作。
        if (oldRoot != null) {
            oldRoot.getChildren().remove(this);
        }
        if (ownerTab != null && ownerTab.getTabPane() != null) {
            ownerTab.setContent(this);
        }
        try {
            // 清除处理器后关闭，避免WINDOW_CLOSE_REQUEST再次进入本方法。
            stage.setOnCloseRequest(null);
            stage.hide();
        } catch (Exception ignored) {
        }
        fullScreenTransitioning = false;
        refreshDesktopAfterSceneChange();
    }

    /**
     * SwingNode跨Scene后重建其原生渲染表面，并在新Scene完成布局后整幅重绘。
     * 单纯repaint只会提交Swing的脏区，多次切换时旧纹理中未标脏的区域会留下黑块。
     */
    private void refreshDesktopAfterSceneChange() {
        final long generation = ++sceneRefreshGeneration;
        final JScrollPane scrollPane = desktopScrollPane;
        if (swingNode == null || scrollPane == null) {
            requestRdpFocus();
            return;
        }

        // 断开并在下一帧重新挂载，强制SwingNode为新的Window/Scene创建渲染表面。
        swingNode.setContent(null);
        Platform.runLater(() -> {
            if (generation != sceneRefreshGeneration || swingNode == null) {
                return;
            }
            swingNode.setContent(scrollPane);
            applyCss();
            layout();
            resizeDesktopViewport();
            requestRdpFocus();

            // 全屏Stage在Windows上通常需要多个脉冲才会完成原生窗口和SwingNode
            // 表面的绑定。分两次主动布局、聚焦和整幅提交，避免必须点击一下才出画面。
            scheduleSceneSettledRefresh(generation, 120);
            scheduleSceneSettledRefresh(generation, 350);
        });
    }

    private void scheduleSceneSettledRefresh(long generation, double delayMillis) {
        PauseTransition settle = new PauseTransition(Duration.millis(delayMillis));
        settle.setOnFinished(e -> {
            if (generation != sceneRefreshGeneration || swingNode == null) {
                return;
            }
            if (fullScreenRoot != null) {
                fullScreenRoot.applyCss();
                fullScreenRoot.layout();
            } else {
                applyCss();
                layout();
            }
            if (swingNode.getParent() != null) {
                swingNode.getParent().requestLayout();
            }
            resizeDesktopViewport();
            JScrollPane scrollPane = desktopScrollPane;
            JComponent display = desktopDisplay;
            if (scrollPane != null && display != null) {
                SwingUtilities.invokeLater(() -> repaintDesktopSynchronously(scrollPane, display));
            }
            requestRdpFocus();
        });
        settle.play();
    }

    private void showExitBar() {
        if (exitBar == null) {
            return;
        }
        if (exitBar.isVisible() && exitBar.getTranslateY() == 0) {
            return;
        }
        exitBar.setVisible(true);
        if (exitBarSlide != null) {
            exitBarSlide.stop();
        }
        exitBarSlide = new TranslateTransition(Duration.millis(200), exitBar);
        exitBarSlide.setToY(0);
        exitBarSlide.play();
    }

    private void startFullScreenEdgeMonitor() {
        if (fullScreenEdgeMouseListener != null) {
            return;
        }
        fullScreenEdgeMouseListener = event -> {
            if (!(event instanceof java.awt.event.MouseEvent mouseEvent)) {
                return;
            }
            int eventId = mouseEvent.getID();
            if (eventId != java.awt.event.MouseEvent.MOUSE_MOVED
                    && eventId != java.awt.event.MouseEvent.MOUSE_DRAGGED
                    && eventId != java.awt.event.MouseEvent.MOUSE_ENTERED) {
                return;
            }
            JScrollPane scrollPane = desktopScrollPane;
            if (scrollPane == null || !(mouseEvent.getSource() instanceof java.awt.Component source)
                    || (source != scrollPane && !SwingUtilities.isDescendingFrom(source, scrollPane))) {
                return;
            }
            java.awt.Point point = SwingUtilities.convertPoint(source, mouseEvent.getPoint(), scrollPane);
            if (isAtFullScreenTopEdge(point.x, point.y, scrollPane.getWidth())) {
                Platform.runLater(() -> {
                    if (fullScreenStage != null) {
                        showExitBar();
                    }
                });
            }
        };
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                    fullScreenEdgeMouseListener,
                    java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "无法启用全屏顶部鼠标监听", e);
            fullScreenEdgeMouseListener = null;
        }
    }

    private void stopFullScreenEdgeMonitor() {
        java.awt.event.AWTEventListener listener = fullScreenEdgeMouseListener;
        fullScreenEdgeMouseListener = null;
        if (listener == null) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "注销全屏顶部鼠标监听失败", e);
        }
    }

    static boolean isAtFullScreenTopEdge(int x, int y, int width) {
        return width > 0 && x >= 0 && x < width && y >= 0 && y <= 5;
    }

    private void hideExitBar() {
        if (exitBarPinned || exitBar == null || !exitBar.isVisible()) {
            return;
        }
        if (exitBarSlide != null) {
            exitBarSlide.stop();
        }
        double hideY = -(exitBar.getHeight() + 8);
        exitBarSlide = new TranslateTransition(Duration.millis(200), exitBar);
        exitBarSlide.setToY(hideY);
        exitBarSlide.setOnFinished(e -> {
            if (exitBar != null) {
                exitBar.setVisible(false);
            }
        });
        exitBarSlide.play();
    }

    private String controlBarButtonStyle(boolean selected) {
        return "-fx-background-color: " + (selected ? "rgba(255,255,255,0.28)" : "transparent") + ";"
                + " -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 3 8 3 8;"
                + " -fx-background-radius: 4; -fx-cursor: hand;";
    }

    private SVGPath createPinIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M14 4V2H10V4L11 5V9L9 11V13H11.25L12 18L12.75 13H15V11L13 9V5Z");
        icon.setFill(Color.WHITE);
        return icon;
    }

    private SVGPath createSignalIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M2 14H4V18H2ZM6 11H8V18H6ZM10 8H12V18H10ZM14 5H16V18H14Z");
        icon.setFill(Color.WHITE);
        return icon;
    }

    private Button createWindowControlButton(String path, String tooltip, boolean closeButton) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setFill(Color.WHITE);
        Button button = new Button();
        button.setGraphic(icon);
        button.setFocusTraversable(false);
        button.setTooltip(createControlTooltip(tooltip));
        String normal = "-fx-background-color: transparent; -fx-background-radius: 0;"
                + " -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0; -fx-cursor: hand;";
        String hover = "-fx-background-color: " + (closeButton ? "#c42b1c" : "rgba(255,255,255,0.22)") + ";"
                + " -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px;"
                + " -fx-padding: 0; -fx-cursor: hand;";
        button.setStyle(normal);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(normal));
        return button;
    }

    private Region createControlBarResizeGrip() {
        Region grip = new Region();
        grip.setMinWidth(7);
        grip.setPrefWidth(7);
        grip.setMaxWidth(7);
        grip.setMaxHeight(Double.MAX_VALUE);
        grip.setCursor(Cursor.H_RESIZE);
        return grip;
    }

    private void installControlBarAdjustment(Label dragArea, Region leftGrip, Region rightGrip) {
        final double[] moveStart = new double[2];
        dragArea.setCursor(Cursor.MOVE);
        dragArea.setOnMousePressed(e -> {
            moveStart[0] = e.getScreenX();
            moveStart[1] = exitBar.getTranslateX();
            e.consume();
        });
        dragArea.setOnMouseDragged(e -> {
            double requested = moveStart[1] + e.getScreenX() - moveStart[0];
            exitBar.setTranslateX(clampControlBarX(requested, exitBar.getWidth()));
            e.consume();
        });

        installResizeGrip(leftGrip, true);
        installResizeGrip(rightGrip, false);
    }

    private void installResizeGrip(Region grip, boolean leftEdge) {
        final double[] start = new double[3];
        grip.setOnMousePressed(e -> {
            start[0] = e.getScreenX();
            start[1] = exitBar.getWidth();
            start[2] = exitBar.getTranslateX();
            e.consume();
        });
        grip.setOnMouseDragged(e -> {
            double delta = e.getScreenX() - start[0];
            double requestedWidth = leftEdge ? start[1] - delta : start[1] + delta;
            double width = Math.max(175, Math.min(760, requestedWidth));
            // StackPane以中心定位；调整单侧边缘时同步移动中心，保持另一侧不动。
            double usedDelta = leftEdge ? start[1] - width : width - start[1];
            double requestedX = start[2] + (leftEdge ? usedDelta / 2 : usedDelta / 2);
            exitBar.setPrefWidth(width);
            exitBar.setMaxWidth(width);
            exitBar.setTranslateX(clampControlBarX(requestedX, width));
            e.consume();
        });
    }

    private double clampControlBarX(double translateX, double barWidth) {
        if (fullScreenRoot == null) {
            return translateX;
        }
        double available = Math.max(0, (fullScreenRoot.getWidth() - barWidth) / 2);
        return Math.max(-available, Math.min(available, translateX));
    }

    private void setControlVisible(Region control, boolean visible) {
        control.setVisible(visible);
        control.setManaged(visible);
    }

    private Tooltip createControlTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(200));
        tooltip.setHideDelay(Duration.millis(100));
        tooltip.setShowDuration(Duration.seconds(8));
        return tooltip;
    }

    private void minimizeFullScreen() {
        Tab tab = ownerTab;
        exitFullScreen();
        // RDP视图还原到其所属窗口后再最小化该窗口；直接最小化全屏Stage会被
        // JavaFX当作“退出全屏”，表现为仅还原窗口而没有真正最小化。
        Platform.runLater(() -> {
            if (tab != null && tab.getTabPane() != null
                    && tab.getTabPane().getScene() != null
                    && tab.getTabPane().getScene().getWindow() instanceof Stage stage) {
                stage.setIconified(true);
            }
        });
    }

    private void closeRemoteDesktop() {
        Tab tab = ownerTab;
        exitFullScreen();
        disconnect();
        if (tab != null && tab.getTabPane() != null) {
            tab.getTabPane().getTabs().remove(tab);
        }
    }

    private void showConnectionQuality() {
        if (hideExitBarTimer != null) {
            hideExitBarTimer.stop();
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (fullScreenStage != null) {
            alert.initOwner(fullScreenStage);
        }
        alert.setTitle("远程桌面连接");
        alert.setHeaderText(null);
        alert.setContentText("与远程计算机连接的质量非常好。");
        alert.showAndWait();
        if (!exitBarPinned && hideExitBarTimer != null) {
            hideExitBarTimer.playFromStart();
        }
    }

    private void resizeDesktopViewport() {
        if (swingNode == null) {
            return;
        }
        final int width = Math.max(1, (int) Math.ceil(swingNode.getLayoutBounds().getWidth()));
        final int height = Math.max(1, (int) Math.ceil(swingNode.getLayoutBounds().getHeight()));
        final JScrollPane scrollPane = desktopScrollPane;
        final JComponent display = desktopDisplay;
        if (scrollPane == null || display == null) {
            return;
        }
        if (fullScreenStage != null) {
            // 全屏模式：滚动容器使用实际屏幕客户区尺寸，不再把SwingNode整体
            // 二次缩放。远程画面较小时由GridBag宿主水平、垂直居中；等大时完整贴合。
            SwingUtilities.invokeLater(() -> {
                java.awt.Dimension canvasSize = display.getPreferredSize();
                java.awt.Dimension viewportSize = new java.awt.Dimension(width, height);
                scrollPane.setPreferredSize(viewportSize);
                scrollPane.setSize(viewportSize);
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                scrollPane.doLayout();
                scrollPane.revalidate();
                display.revalidate();
                display.repaint();
                scrollPane.getViewport().revalidate();
                scrollPane.getViewport().repaint();
                scrollPane.repaint();
                // 关键：WrappedImage.update()按裁剪区增量绘制，尺寸放大后新暴露的
                // 区域不会被自动调度重绘（SwingNode内非标准布局路径），表现为黑方块、
                // 鼠标划过才逐块补画。paintImmediately同步整幅绘制，彻底消除黑块。
                display.paintImmediately(0, 0, canvasSize.width, canvasSize.height);
                scrollPane.paintImmediately(0, 0, viewportSize.width, viewportSize.height);
            });
            swingNode.getTransforms().clear();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            java.awt.Dimension viewportSize = new java.awt.Dimension(width, height);
            scrollPane.setPreferredSize(viewportSize);
            scrollPane.setSize(viewportSize);
            scrollPane.doLayout();
            scrollPane.revalidate();
            display.revalidate();
            display.repaint();
            scrollPane.getViewport().revalidate();
            scrollPane.getViewport().repaint();
            scrollPane.repaint();
        });
        scheduleViewportRefresh();
    }

    private void applyWindows10ScrollBars(JScrollPane scrollPane) {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        vertical.setUI(new Windows10ScrollBarUI());
        vertical.setPreferredSize(new Dimension(14, 0));
        vertical.setUnitIncrement(24);

        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        horizontal.setUI(new Windows10ScrollBarUI());
        horizontal.setPreferredSize(new Dimension(0, 14));
        horizontal.setUnitIncrement(24);

        JPanel corner = new JPanel();
        corner.setBackground(new java.awt.Color(240, 240, 240));
        scrollPane.setCorner(JScrollPane.LOWER_RIGHT_CORNER, corner);
    }

    /** 首次打开独立窗口时使用：客户区校准完成前不让滚动条反向挤压画布。 */
    public void suppressInitialWindowScrollBars() {
        windowScrollBarsSuppressed = true;
        JScrollPane scrollPane = desktopScrollPane;
        if (scrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            });
        }
    }

    /** 用户首次手动调整窗口后恢复按需滚动条。 */
    public void enableWindowScrollBars() {
        if (!windowScrollBarsSuppressed) {
            return;
        }
        windowScrollBarsSuppressed = false;
        JScrollPane scrollPane = desktopScrollPane;
        if (scrollPane != null && fullScreenStage == null) {
            SwingUtilities.invokeLater(() -> {
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
                scrollPane.revalidate();
                scrollPane.repaint();
            });
        }
    }

    /** Win10风格：浅灰轨道、窄灰色滑块、悬停加深，不显示厚重的Metal箭头按钮。 */
    private static final class Windows10ScrollBarUI extends BasicScrollBarUI {
        private static final java.awt.Color TRACK = new java.awt.Color(240, 240, 240);
        private static final java.awt.Color THUMB = new java.awt.Color(193, 193, 193);
        private static final java.awt.Color THUMB_HOVER = new java.awt.Color(168, 168, 168);

        @Override
        protected void configureScrollBarColors() {
            trackColor = TRACK;
            thumbColor = THUMB;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createZeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createZeroButton();
        }

        private JButton createZeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setMinimumSize(zero);
            button.setPreferredSize(zero);
            button.setMaximumSize(zero);
            return button;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(TRACK);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? THUMB_HOVER : THUMB);
            if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
                g2.fillRoundRect(bounds.x + 3, bounds.y + 1,
                        Math.max(4, bounds.width - 6), Math.max(4, bounds.height - 2), 4, 4);
            } else {
                g2.fillRoundRect(bounds.x + 1, bounds.y + 3,
                        Math.max(4, bounds.width - 2), Math.max(4, bounds.height - 6), 4, 4);
            }
            g2.dispose();
        }
    }

    /**
     * 窗口拖动期间resize事件非常密集，结束后再补一次同步整幅绘制，确保SwingNode
     * 的离屏纹理和Swing组件最终尺寸一致，避免首次进入或调整窗口后的黑色方块。
     */
    private void scheduleViewportRefresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::scheduleViewportRefresh);
            return;
        }
        if (viewportRefreshTimer == null) {
            viewportRefreshTimer = new PauseTransition(Duration.millis(100));
            viewportRefreshTimer.setOnFinished(e -> {
                JScrollPane scrollPane = desktopScrollPane;
                JComponent display = desktopDisplay;
                if (scrollPane != null && display != null) {
                    SwingUtilities.invokeLater(() -> repaintDesktopSynchronously(scrollPane, display));
                }
            });
        }
        viewportRefreshTimer.playFromStart();
    }

    /** 必须在Swing EDT调用。 */
    private void repaintDesktopSynchronously(JScrollPane scrollPane, JComponent display) {
        java.awt.Dimension canvasSize = display.getPreferredSize();
        display.paintImmediately(0, 0, canvasSize.width, canvasSize.height);
        int width = Math.max(1, scrollPane.getWidth());
        int height = Math.max(1, scrollPane.getHeight());
        scrollPane.paintImmediately(0, 0, width, height);
        javax.swing.RepaintManager.currentManager(display).paintDirtyRegions();
    }

    /**
     * 全屏时把远程桌面画面等比缩放铺满屏幕（保持宽高比、居中显示，无滚动条）
     */
    private void applyFullScreenScale() {
        if (swingNode == null || fullScreenStage == null) {
            return;
        }
        double areaW = swingNode.getLayoutBounds().getWidth();
        double areaH = swingNode.getLayoutBounds().getHeight();
        if (areaW <= 0 || areaH <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        double scale = Math.min(areaW / screenWidth, areaH / screenHeight);
        if (!Double.isFinite(scale) || scale <= 0) {
            return;
        }
        swingNode.getTransforms().clear();
        if (Math.abs(scale - 1.0) > 0.001) {
            // 以swingNode中心为轴缩放，保持画面居中
            swingNode.getTransforms().add(new Scale(scale, scale, areaW / 2, areaH / 2));
        }
    }

    private void updateStatus(ConnectionState state) {
        switch (state) {
            case DISCONNECTED:
                statusDot.setFill(Color.GRAY);
                stateTextField.setText("未连接");
                break;
            case CONNECTING:
                statusDot.setFill(Color.ORANGE);
                stateTextField.setText("连接中...");
                break;
            case CONNECTED:
                statusDot.setFill(Color.GREEN);
                stateTextField.setText("已连接");
                break;
            case ERROR:
                statusDot.setFill(Color.RED);
                stateTextField.setText("连接失败");
                break;
        }
    }

    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }
}
