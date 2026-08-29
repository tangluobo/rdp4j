package com.tangluobo.tomato.rdp;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
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

        // 更新状态栏
        updateStatus(ConnectionState.CONNECTING);
        connLabel.setText(username + "@" + host + ":" + port);
        resolutionLabel.setText(screenWidth + "x" + screenHeight + " @" + colorDepth);

        // 先显示加载占位面板（Swing组件在EDT创建，SwingNode.setContent在JavaFX线程）
        SwingUtilities.invokeLater(() -> {
            JPanel loadingPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
            loadingPanel.setBackground(java.awt.Color.WHITE);
            JLabel loadingLabel = new JLabel("正在连接到 " + host + " ...");
            loadingLabel.setFont(loadingLabel.getFont().deriveFont(java.awt.Font.PLAIN, 14));
            loadingPanel.add(loadingLabel);
            Platform.runLater(() -> swingNode.setContent(loadingPanel));
        });

        // 设置连接就绪回调 - 连接成功后才设置画布到SwingNode
        rdpClient.setOnConnected(v -> {
            final JComponent displayComponent = rdpClient.getDisplayComponent();
            if (displayComponent == null) {
                logger.warning("RDP显示组件为null，无法显示");
                Platform.runLater(() -> updateStatus(ConnectionState.ERROR));
                return;
            }
            logger.info("RDP显示组件: " + displayComponent.getClass().getSimpleName()
                    + " size=" + displayComponent.getSize()
                    + " prefSize=" + displayComponent.getPreferredSize());
            // 启用本地输入法；Input会把已提交文本转换为RDP Unicode键盘事件，
            // 组合中的拼音仍只在本地输入法中处理，不会提前发送到远端。
            displayComponent.enableInputMethods(true);

            // 在EDT上用JScrollPane包装显示组件（WrappedImage实现了Scrollable）
            SwingUtilities.invokeLater(() -> {
                displayComponent.setSize(displayComponent.getPreferredSize());
                JScrollPane scrollPane = new JScrollPane(displayComponent);
                scrollPane.setBackground(java.awt.Color.BLACK);
                scrollPane.getViewport().setBackground(java.awt.Color.BLACK);
                // 去掉Metal LAF默认的四边线边框（全屏时会呈现为屏幕四周的白线）
                scrollPane.setBorder(null);
                scrollPane.getViewport().setBorder(null);
                scrollPane.setDoubleBuffered(true);
                desktopDisplay = displayComponent;
                desktopScrollPane = scrollPane;
                // 在JavaFX Application Thread上设置SwingNode内容
                Platform.runLater(() -> {
                    swingNode.setContent(scrollPane);
                    resizeDesktopViewport();
                    SwingUtilities.invokeLater(() -> {
                        displayComponent.revalidate();
                        displayComponent.repaint();
                        displayComponent.requestFocusInWindow();
                    });
                    // DISPLAY ready 仅说明协议握手已完成；等待首个 bitmap 后再显示已连接。
                });
            });
            // 诊断日志已在RdpPatch.processBitmapUpdates中实现，此处不再重复
        });

        // 设置断开回调
        rdpClient.setOnDisconnected(reason -> {
            Platform.runLater(() -> {
                updateStatus(ConnectionState.DISCONNECTED);
                stateTextField.setText("已断开: " + reason);
            });
        });

        rdpClient.setOnFirstFrame(v -> Platform.runLater(() -> updateStatus(ConnectionState.CONNECTED)));

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

    /**
     * 断开连接
     */
    public void disconnect() {
        // 断开前先退出全屏，把组件还原到tab中，避免全屏窗口残留
        if (fullScreenStage != null) {
            exitFullScreen();
        }
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
        // tab内容用占位面板顶替，保持tab结构不变
        ownerTab.setContent(new StackPane());
        // 全屏时隐藏状态栏，只显示远程桌面
        setBottom(null);
        // 全屏期间背景全黑：缩放取整产生的边缘缝隙显示为黑色而不是浅色底
        setStyle("-fx-background-color: black;");

        // 顶部悬浮"退出全屏"按钮
        exitBar = new HBox();
        exitBar.setAlignment(Pos.CENTER);
        exitBar.setStyle("-fx-background-color: rgba(0,120,215,0.92); -fx-background-radius: 0 0 8 8;"
                + " -fx-padding: 5 14 5 14; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 6, 0.3, 0, 2);");
        Button exitBtn = new Button("退出全屏");
        exitBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 12px;"
                + " -fx-padding: 2 4 2 4; -fx-cursor: hand;");
        exitBtn.setOnAction(e -> exitFullScreen());
        exitBar.getChildren().add(exitBtn);
        exitBar.setVisible(false);
        exitBar.setManaged(false);
        exitBar.setTranslateY(-40);
        // 鼠标悬停在按钮上时保持显示，移开后计时隐藏
        exitBar.setOnMouseEntered(e -> { if (hideExitBarTimer != null) hideExitBarTimer.stop(); });
        exitBar.setOnMouseExited(e -> { if (hideExitBarTimer != null) hideExitBarTimer.playFromStart(); });

        hideExitBarTimer = new PauseTransition(Duration.millis(2500));
        hideExitBarTimer.setOnFinished(e -> hideExitBar());

        // 顶部透明感应条：SwingNode会拦截鼠标事件导致Scene过滤器收不到，
        // 用一个覆盖顶部5px的FX节点通过MOUSE_ENTERED可靠触发退出按钮
        Region topEdgeTrigger = new Region();
        topEdgeTrigger.setPrefHeight(5);
        topEdgeTrigger.setMaxHeight(5);
        topEdgeTrigger.setStyle("-fx-background-color: transparent;");
        topEdgeTrigger.setOnMouseEntered(e -> showExitBar());
        topEdgeTrigger.setOnMouseMoved(e -> showExitBar());
        StackPane.setAlignment(topEdgeTrigger, Pos.TOP_CENTER);

        fullScreenRoot = new StackPane(this);
        fullScreenRoot.setStyle("-fx-background-color: black;");
        fullScreenRoot.getChildren().addAll(topEdgeTrigger, exitBar);
        // managed=false时StackPane不再自动布局exitBar，手动绑定顶部居中定位
        // （layoutY保持0贴顶，隐藏态通过translateY滑出屏幕外实现）
        exitBar.layoutXProperty().bind(
                fullScreenRoot.widthProperty().subtract(exitBar.widthProperty()).divide(2));

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
            fullScreenTransitioning = false;
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
        exitBar = null;
        hideExitBarTimer = null;
        exitBarSlide = null;
        // 恢复缩放（窗口模式1:1显示）
        if (swingNode != null) {
            swingNode.getTransforms().clear();
        }
        // 恢复滚动条策略
        final JScrollPane scrollPane = desktopScrollPane;
        if (scrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
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

            // 再等一次布局稳定后同步提交整张远程桌面，覆盖增量脏区遗漏。
            PauseTransition settle = new PauseTransition(Duration.millis(120));
            settle.setOnFinished(e -> {
                if (generation == sceneRefreshGeneration) {
                    resizeDesktopViewport();
                    requestRdpFocus();
                }
            });
            settle.play();
        });
    }

    private void showExitBar() {
        if (exitBar == null || exitBar.isVisible()) {
            return;
        }
        exitBar.setVisible(true);
        if (exitBarSlide != null) {
            exitBarSlide.stop();
        }
        exitBarSlide = new TranslateTransition(Duration.millis(200), exitBar);
        exitBarSlide.setToY(0);
        exitBarSlide.play();
        if (hideExitBarTimer != null) {
            hideExitBarTimer.playFromStart();
        }
    }

    private void hideExitBar() {
        if (exitBar == null || !exitBar.isVisible()) {
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
            // 全屏模式：视口与远程桌面同尺寸并隐藏滚动条（画面不裁剪），
            // 通过swingNode上的Scale变换等比缩放铺满整个屏幕
            SwingUtilities.invokeLater(() -> {
                java.awt.Dimension canvasSize = display.getPreferredSize();
                scrollPane.setPreferredSize(canvasSize);
                scrollPane.setSize(canvasSize);
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
                scrollPane.paintImmediately(0, 0, canvasSize.width, canvasSize.height);
            });
            applyFullScreenScale();
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
