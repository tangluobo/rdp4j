package com.tangluobo.rdp4j;

import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.rdp4j.frontend.FxRdpDisplay;
import com.tangluobo.rdp4j.frontend.FxRdpFrontend;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Pure JavaFX RDP container. Swing remains available through SwingRdpFrontend. */
public class RdpPane extends BorderPane {

    private static final Logger logger = Logger.getLogger(RdpPane.class.getName());
    private static final double FULL_SCREEN_TOP_EDGE_HEIGHT = 12;
    private static final double CONTROL_BAR_HANDLE_HEIGHT = 3;
    private static final double NESTED_CONTROL_BAR_OFFSET = 44;
    private static final long EDGE_POLL_INTERVAL_NANOS = 16_000_000L;

    private final FxRdpFrontend frontend = new FxRdpFrontend();
    private final RdpClient rdpClient = new RdpClient(frontend);
    private final Set<KeyCode> locallyConsumedKeys = EnumSet.noneOf(KeyCode.class);
    private ScrollPane desktopScrollPane;
    private Node desktopView;
    private boolean windowScrollBarsSuppressed;

    private HBox statusBar;
    private Circle statusDot;
    private TextField stateTextField;
    private Label connLabel;
    private Label resolutionLabel;

    private Tab ownerTab;
    private Stage fullScreenStage;
    private StackPane fullScreenRoot;
    private HBox exitBar;
    private PauseTransition hideExitBarTimer;
    private TranslateTransition exitBarSlide;
    private AnimationTimer fullScreenEdgeMouseMonitor;
    private boolean exitBarPinned;
    private boolean exitBarDragging;
    private double exitBarDragStartSceneX;
    private double exitBarDragStartTranslateX;
    private boolean fullScreenTransitioning;
    private double exitBarShownY;
    private int bestSceneEdgeBand = Integer.MAX_VALUE;
    private int bestLocalRemoteEdgeBand = Integer.MAX_VALUE;
    private int bestServerRemoteEdgeBand = Integer.MAX_VALUE;
    private int bestRobotEdgeBand = Integer.MAX_VALUE;
    private ScrollPane.ScrollBarPolicy verticalPolicyBeforeFullScreen = ScrollPane.ScrollBarPolicy.AS_NEEDED;
    private ScrollPane.ScrollBarPolicy horizontalPolicyBeforeFullScreen = ScrollPane.ScrollBarPolicy.AS_NEEDED;

    private String host;
    private int port;
    private int screenWidth;
    private int screenHeight;
    private int colorDepth;
    private volatile KeyCombination fullScreenKeys = KeyCombination.valueOf("Ctrl+Shift+Enter");

    public RdpPane() {
        frontend.setPointerMovedListener(this::onRemotePointerMoved);
        frontend.setServerPointerMovedListener(this::onServerPointerMoved);
        initializeUi();
    }

    private void initializeUi() {
        setCenter(createLoadingView("未连接"));
        statusBar = createStatusBar();
        setBottom(statusBar);
        getStyleClass().add("rdp-pane");

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (fullScreenKeys != null && fullScreenKeys.match(event)) {
                locallyConsumedKeys.add(event.getCode());
                event.consume();
                rdpClient.releaseRemoteModifierKeys();
                toggleFullScreen();
            }
        });
        addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (locallyConsumedKeys.remove(event.getCode())) {
                event.consume();
            }
        });
    }

    private Node createLoadingView(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.rgb(230, 230, 230));
        label.setStyle("-fx-font-size: 14px;");
        StackPane pane = new StackPane(label);
        pane.setStyle("-fx-background-color: #202020;");
        return pane;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc transparent transparent transparent;"
                + " -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(5, Color.GRAY);
        stateTextField = new TextField("未连接");
        stateTextField.setEditable(false);
        stateTextField.setFocusTraversable(true);
        stateTextField.setMinWidth(80);
        stateTextField.setPrefWidth(220);
        stateTextField.setMaxWidth(360);
        stateTextField.setStyle("-fx-font-size: 11px; -fx-background-color: transparent;"
                + " -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");
        Tooltip fullStatus = new Tooltip();
        fullStatus.textProperty().bind(stateTextField.textProperty());
        fullStatus.setWrapText(true);
        fullStatus.setMaxWidth(600);
        stateTextField.setTooltip(fullStatus);

        Label separator = new Label("|");
        separator.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        connLabel = new Label();
        connLabel.setStyle("-fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        resolutionLabel = new Label();
        resolutionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        bar.getChildren().addAll(statusDot, stateTextField, separator, connLabel, spacer, resolutionLabel);
        return bar;
    }

    public void connect(String host, int port, String username, String password,
                        String domain, int screenWidth, int screenHeight, int colorDepth,
                        boolean useSsl, boolean mapClipboard, boolean enableSound) {
        this.host = host;
        this.port = port;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.colorDepth = colorDepth;
        desktopScrollPane = null;
        desktopView = null;

        runOnFxThread(() -> {
            updateStatus(ConnectionState.CONNECTING);
            connLabel.setText(username + "@" + host + ":" + port);
            resolutionLabel.setText(screenWidth + "x" + screenHeight + " @" + colorDepth);
            setCenter(createLoadingView("正在连接到 " + host + " ..."));
        });

        rdpClient.setOnConnected(ignored -> logger.info("RDP显示通道已就绪，等待首帧"));
        rdpClient.setOnFirstFrame(ignored -> runOnFxThread(this::attachDesktopAfterFirstFrame));
        rdpClient.setOnDisconnected(reason -> runOnFxThread(() -> {
            updateStatus(ConnectionState.DISCONNECTED);
            stateTextField.setText("已断开: " + reason);
        }));

        try {
            rdpClient.connect(host, port, username, password, domain, screenWidth, screenHeight,
                    colorDepth, useSsl, mapClipboard, enableSound);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "RDP连接失败: " + e.getMessage(), e);
            runOnFxThread(() -> {
                updateStatus(ConnectionState.ERROR);
                stateTextField.setText("连接失败: " + e.getMessage());
            });
        }
    }

    private void attachDesktopAfterFirstFrame() {
        Node nextView = frontend.getView();
        if (nextView == null) {
            updateStatus(ConnectionState.ERROR);
            stateTextField.setText("显示初始化失败");
            return;
        }
        desktopView = nextView;
        ScrollPane scrollPane = new ScrollPane(nextView);
        scrollPane.setPannable(false);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        scrollPane.setStyle("-fx-background: black; -fx-background-color: black;");
        ScrollPane.ScrollBarPolicy initialPolicy = windowScrollBarsSuppressed
                ? ScrollPane.ScrollBarPolicy.NEVER : ScrollPane.ScrollBarPolicy.AS_NEEDED;
        scrollPane.setVbarPolicy(initialPolicy);
        scrollPane.setHbarPolicy(initialPolicy);
        desktopScrollPane = scrollPane;
        setCenter(scrollPane);
        if (fullScreenStage != null) {
            applyFullScreenPresentation(true);
        }
        updateStatus(ConnectionState.CONNECTED);
        Platform.runLater(this::requestRdpFocus);
    }

    public void disconnect() {
        if (fullScreenStage != null) {
            exitFullScreen();
        }
        stopFullScreenEdgeMonitor();
        locallyConsumedKeys.clear();
        rdpClient.disconnect();
        desktopScrollPane = null;
        desktopView = null;
        setCenter(createLoadingView("未连接"));
        updateStatus(ConnectionState.DISCONNECTED);
    }

    public boolean isConnected() {
        return rdpClient.isConnected();
    }

    public void requestRdpFocus() {
        FxRdpDisplay display = frontend.getDisplay();
        if (display != null) {
            display.getImageView().requestFocus();
            rdpClient.synchronizeKeyboardState();
        }
    }

    public void setOwnerTab(Tab ownerTab) {
        this.ownerTab = ownerTab;
    }

    public boolean isFullScreen() {
        return fullScreenStage != null;
    }

    public void setFullScreenShortcut(String shortcutText) {
        String text = shortcutText == null || shortcutText.isBlank()
                ? "Ctrl+Shift+Enter" : shortcutText.trim();
        try {
            fullScreenKeys = KeyCombination.valueOf(text);
            Stage stage = fullScreenStage;
            if (stage != null) {
                stage.setFullScreenExitKeyCombination(fullScreenKeys);
                stage.setFullScreenExitHint("按 " + fullScreenKeys.getName() + " 退出全屏");
            }
        } catch (IllegalArgumentException ignored) {
            // Keep the last valid shortcut.
        }
    }

    public String getFullScreenShortcutText() {
        return fullScreenKeys == null ? "Ctrl+Shift+Enter" : fullScreenKeys.getName();
    }

    public void toggleFullScreen() {
        if (fullScreenTransitioning) {
            return;
        }
        if (fullScreenStage == null) {
            enterFullScreen();
        } else {
            exitFullScreen();
        }
    }

    private void enterFullScreen() {
        if (ownerTab == null || fullScreenStage != null || fullScreenTransitioning) {
            return;
        }
        rdpClient.releaseRemoteModifierKeys();
        fullScreenTransitioning = true;
        resetFullScreenEdgeDiagnostics();
        if (desktopScrollPane != null) {
            verticalPolicyBeforeFullScreen = desktopScrollPane.getVbarPolicy();
            horizontalPolicyBeforeFullScreen = desktopScrollPane.getHbarPolicy();
        }
        ownerTab.setContent(new StackPane());
        setBottom(null);
        setStyle("-fx-background-color: black;");
        applyFullScreenPresentation(true);

        exitBar = createExitBar();
        exitBarShownY = controlBarOffsetForSession(
                System.getenv("SESSIONNAME"), System.getenv("XRDP_SESSION"));
        exitBar.setTranslateY(exitBarShownY);
        StackPane.setAlignment(exitBar, Pos.TOP_CENTER);

        fullScreenRoot = new StackPane(this, exitBar);
        fullScreenRoot.setStyle("-fx-background-color: black;");
        Scene scene = new Scene(fullScreenRoot, Color.BLACK);
        // Observe every mouse event at scene level without consuming it. Some
        // servers reposition the pointer or use a software cursor, so relying
        // on MOUSE_MOVED from the remote image alone is not portable.
        scene.addEventFilter(MouseEvent.ANY, event -> {
            logCloserEdgeObservation("scene", event.getSceneY(), 0);
            if (isAtSceneTopEdge(event.getSceneY())) {
                showExitBar("scene");
            }
        });

        Stage stage = new Stage();
        fullScreenStage = stage;
        stage.setTitle(ownerTab.getText() == null ? "远程桌面" : ownerTab.getText());
        stage.setScene(scene);
        stage.setFullScreenExitKeyCombination(fullScreenKeys);
        stage.setFullScreenExitHint("按 " + fullScreenKeys.getName() + " 退出全屏");
        positionOnOwnerScreen(stage);
        stage.fullScreenProperty().addListener((observable, wasFullScreen, isFullScreen) -> {
            if (wasFullScreen && !isFullScreen && !fullScreenTransitioning && fullScreenStage == stage) {
                exitFullScreen();
            }
        });
        stage.setOnCloseRequest(event -> {
            event.consume();
            exitFullScreen();
        });
        stage.show();
        startFullScreenEdgeMonitor();
        Platform.runLater(() -> {
            if (fullScreenStage != stage) {
                fullScreenTransitioning = false;
                return;
            }
            stage.setFullScreen(true);
            fullScreenTransitioning = false;
            showExitBar("initial");
            Platform.runLater(() -> logControlBarState("entered"));
            requestRdpFocus();
        });
    }

    private HBox createExitBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER);
        bar.setPrefWidth(420);
        bar.setMaxWidth(420);
        bar.setMinHeight(Region.USE_PREF_SIZE);
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-background-color: linear-gradient(to bottom, #3d8bd2, #07519a);"
                + " -fx-background-radius: 0 0 8 8; -fx-border-color: #75b8f0;"
                + " -fx-border-width: 0 1 1 1; -fx-border-radius: 0 0 8 8;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 7, 0.3, 0, 2);");

        ToggleButton pin = new ToggleButton();
        pin.setFocusTraversable(false);
        pin.setGraphic(createPinIcon());
        pin.setTooltip(createTooltip("固定控制栏"));
        pin.setStyle(controlBarButtonStyle(false));
        pin.selectedProperty().addListener((observable, oldValue, selected) -> {
            exitBarPinned = selected;
            pin.setStyle(controlBarButtonStyle(selected));
            if (selected) {
                if (hideExitBarTimer != null) hideExitBarTimer.stop();
                showExitBar();
            } else if (hideExitBarTimer != null) {
                hideExitBarTimer.playFromStart();
            }
        });

        Label title = new Label(ownerTab.getText() == null ? "远程桌面" : ownerTab.getText());
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setCursor(Cursor.H_RESIZE);
        title.setTooltip(createTooltip("左右拖动控制栏"));
        title.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        HBox.setHgrow(title, Priority.ALWAYS);

        Button quality = createControlButton("M2 14H4V18H2ZM6 11H8V18H6ZM10 8H12V18H10ZM14 5H16V18H14Z", "连接质量", false);
        quality.setOnAction(event -> showConnectionQuality());
        Button minimize = createControlButton("M6 13.5h12v-1H6v1z", "最小化", false);
        minimize.setOnAction(event -> minimizeFullScreen());
        Button exit = createControlButton("M4 6h12v12H4V6zm1 1v10h10V7H5z M7 3h12v1H7z M18 3h1v12h-1z", "退出全屏", false);
        exit.setOnAction(event -> exitFullScreen());
        Button close = createControlButton("M5 6.5L5.5 6 12 12.5 18.5 6 19 6.5 12.5 13 19 19.5 18.5 20 12 13.5 5.5 20 5 19.5 11.5 13Z", "关闭远程桌面", true);
        close.setOnAction(event -> closeRemoteDesktop());
        bar.getChildren().addAll(pin, quality, title, minimize, exit, close);
        installExitBarHorizontalDrag(bar);
        bar.setOnMouseEntered(event -> {
            if (hideExitBarTimer != null) hideExitBarTimer.stop();
            showExitBar("handle");
        });
        bar.setOnMouseExited(event -> {
            if (!exitBarPinned && hideExitBarTimer != null) hideExitBarTimer.playFromStart();
        });
        hideExitBarTimer = new PauseTransition(Duration.seconds(5));
        hideExitBarTimer.setOnFinished(event -> hideExitBar());
        return bar;
    }

    private void installExitBarHorizontalDrag(HBox bar) {
        bar.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY
                    || isInsideControlButton(event.getTarget(), bar)) {
                return;
            }
            exitBarDragging = true;
            exitBarDragStartSceneX = event.getSceneX();
            exitBarDragStartTranslateX = bar.getTranslateX();
            if (hideExitBarTimer != null) {
                hideExitBarTimer.stop();
            }
            event.consume();
        });
        bar.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!exitBarDragging || !event.isPrimaryButtonDown()) {
                return;
            }
            double containerWidth = fullScreenRoot == null ? 0 : fullScreenRoot.getWidth();
            double barWidth = bar.getWidth() > 0 ? bar.getWidth() : bar.prefWidth(-1);
            double requestedX = exitBarDragStartTranslateX
                    + event.getSceneX() - exitBarDragStartSceneX;
            bar.setTranslateX(clampControlBarTranslateX(requestedX, containerWidth, barWidth));
            event.consume();
        });
        bar.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!exitBarDragging || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            exitBarDragging = false;
            if (!exitBarPinned && hideExitBarTimer != null && !bar.isHover()) {
                hideExitBarTimer.playFromStart();
            }
            event.consume();
        });
    }

    private static boolean isInsideControlButton(Object target, HBox bar) {
        if (!(target instanceof Node node)) {
            return false;
        }
        while (node != null && node != bar) {
            if (node instanceof ButtonBase) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    static double clampControlBarTranslateX(double requestedX, double containerWidth,
                                            double barWidth) {
        if (!Double.isFinite(requestedX) || !Double.isFinite(containerWidth)
                || !Double.isFinite(barWidth) || containerWidth <= 0 || barWidth <= 0) {
            return 0;
        }
        double maxOffset = Math.max(0, (containerWidth - barWidth) / 2);
        return Math.max(-maxOffset, Math.min(maxOffset, requestedX));
    }

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
        if (hideExitBarTimer != null) hideExitBarTimer.stop();
        if (exitBarSlide != null) exitBarSlide.stop();
        hideExitBarTimer = null;
        exitBarSlide = null;
        exitBar = null;
        exitBarPinned = false;
        exitBarDragging = false;
        applyFullScreenPresentation(false);
        // An independent window can suppress its bars while its initial size
        // is fitted to a windowed desktop. Once a full-screen desktop returns
        // to that smaller window, the remote raster must stay at native size
        // and both scroll bars must be available as needed.
        enableWindowScrollBars();
        setBottom(statusBar);
        setStyle(null);
        if (oldRoot != null) {
            oldRoot.getChildren().remove(this);
        }
        if (ownerTab != null && ownerTab.getTabPane() != null) {
            ownerTab.setContent(this);
        }
        stage.setOnCloseRequest(null);
        stage.hide();
        fullScreenTransitioning = false;
        Platform.runLater(this::requestRdpFocus);
    }

    private void applyFullScreenPresentation(boolean fullScreen) {
        frontend.setScaleToFit(fullScreen);
        ScrollPane scrollPane = desktopScrollPane;
        if (scrollPane == null) {
            return;
        }
        scrollPane.setFitToWidth(fullScreen);
        scrollPane.setFitToHeight(fullScreen);
        if (fullScreen) {
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        } else {
            scrollPane.setVbarPolicy(verticalPolicyBeforeFullScreen);
            scrollPane.setHbarPolicy(horizontalPolicyBeforeFullScreen);
        }
    }

    private void positionOnOwnerScreen(Stage stage) {
        try {
            if (ownerTab.getTabPane() == null || ownerTab.getTabPane().getScene() == null) return;
            Window owner = ownerTab.getTabPane().getScene().getWindow();
            if (owner == null) return;
            Rectangle2D probe = new Rectangle2D(owner.getX(), owner.getY(), 1, 1);
            for (Screen screen : Screen.getScreensForRectangle(probe)) {
                stage.setX(screen.getBounds().getMinX());
                stage.setY(screen.getBounds().getMinY());
                break;
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void showExitBar() {
        showExitBar("internal");
    }

    private void showExitBar(String source) {
        if (exitBar == null) {
            return;
        }
        boolean wasHidden = !exitBar.isVisible()
                || Math.abs(exitBar.getTranslateY() - exitBarShownY) > 0.5;
        if (exitBarSlide != null) exitBarSlide.stop();
        exitBar.setVisible(true);
        exitBar.setTranslateY(exitBarShownY);
        if (wasHidden) {
            logControlBarState("shown-by-" + source);
        }
        if (!exitBarPinned && hideExitBarTimer != null && !exitBar.isHover()) {
            hideExitBarTimer.playFromStart();
        }
    }

    private void onRemotePointerMoved(int remoteX, int remoteY) {
        onProtocolPointerMoved("rdp-local", remoteX, remoteY, false);
    }

    private void onServerPointerMoved(int remoteX, int remoteY) {
        onProtocolPointerMoved("rdp-server", remoteX, remoteY, true);
    }

    private void onProtocolPointerMoved(String source, int remoteX, int remoteY,
                                        boolean serverPointer) {
        if (fullScreenStage == null) {
            return;
        }
        FxRdpDisplay display = frontend.getDisplay();
        if (display == null) {
            return;
        }
        double renderedHeight = display.getImageView().getBoundsInParent().getHeight();
        logCloserEdgeObservation(source, remoteY, serverPointer ? 2 : 1);
        if (isAtRemoteTopEdge(remoteY, display.getDisplayHeight(), renderedHeight)) {
            showExitBar(source + "(" + remoteX + "," + remoteY + ")");
        }
    }

    private void hideExitBar() {
        if (exitBarPinned || exitBar == null) {
            return;
        }
        if (exitBarSlide != null) exitBarSlide.stop();
        exitBarSlide = new TranslateTransition(Duration.millis(180), exitBar);
        // Keep a narrow, visible and pickable handle at the physical top
        // centre. It can reveal the bar even when a legacy/software remote
        // cursor path prevents scene-level edge coordinates from arriving.
        exitBarSlide.setToY(hiddenControlBarTranslateY(exitBar.getHeight()));
        exitBarSlide.setOnFinished(event -> logControlBarState("hidden"));
        exitBarSlide.play();
    }

    static double hiddenControlBarTranslateY(double barHeight) {
        if (!Double.isFinite(barHeight) || barHeight <= CONTROL_BAR_HANDLE_HEIGHT) {
            return 0;
        }
        return -(barHeight - CONTROL_BAR_HANDLE_HEIGHT);
    }

    private void startFullScreenEdgeMonitor() {
        stopFullScreenEdgeMonitor();
        final Robot robot;
        try {
            robot = new Robot();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "无法启用全屏顶部鼠标监听", e);
            return;
        }
        fullScreenEdgeMouseMonitor = new AnimationTimer() {
            private long lastCheck;

            @Override
            public void handle(long now) {
                if (now - lastCheck < EDGE_POLL_INTERVAL_NANOS) return;
                lastCheck = now;
                Stage stage = fullScreenStage;
                if (stage == null || !stage.isShowing()) return;
                var mouse = robot.getMousePosition();
                Rectangle2D edgeBounds = resolveFullScreenEdgeBounds(stage);
                logCloserEdgeObservation("robot", mouse.getY() - edgeBounds.getMinY(), 3);
                if (isAtFullScreenTopEdge(mouse.getX(), mouse.getY(),
                        edgeBounds.getMinX(), edgeBounds.getMinY(), edgeBounds.getWidth())) {
                    showExitBar("robot");
                }
            }
        };
        fullScreenEdgeMouseMonitor.start();
    }

    private void resetFullScreenEdgeDiagnostics() {
        bestSceneEdgeBand = Integer.MAX_VALUE;
        bestLocalRemoteEdgeBand = Integer.MAX_VALUE;
        bestServerRemoteEdgeBand = Integer.MAX_VALUE;
        bestRobotEdgeBand = Integer.MAX_VALUE;
    }

    private void logCloserEdgeObservation(String source, double y, int sourceIndex) {
        int band = edgeDiagnosticBand(y);
        int previous = switch (sourceIndex) {
        case 0 -> bestSceneEdgeBand;
        case 1 -> bestLocalRemoteEdgeBand;
        case 2 -> bestServerRemoteEdgeBand;
        default -> bestRobotEdgeBand;
        };
        if (band >= previous) {
            return;
        }
        switch (sourceIndex) {
        case 0 -> bestSceneEdgeBand = band;
        case 1 -> bestLocalRemoteEdgeBand = band;
        case 2 -> bestServerRemoteEdgeBand = band;
        default -> bestRobotEdgeBand = band;
        }
        logger.info(() -> "[FULLSCREEN_EDGE] source=" + source + ", y=" + y
                + ", band=" + band + ", host=" + host);
    }

    static int edgeDiagnosticBand(double y) {
        if (!Double.isFinite(y) || y < 0 || y > 200) return Integer.MAX_VALUE;
        if (y <= FULL_SCREEN_TOP_EDGE_HEIGHT) return 0;
        if (y <= 25) return 1;
        if (y <= 50) return 2;
        if (y <= 100) return 3;
        return 4;
    }

    private void logControlBarState(String state) {
        HBox bar = exitBar;
        Stage stage = fullScreenStage;
        if (bar == null || stage == null) {
            return;
        }
        Bounds screenBounds = bar.localToScreen(bar.getLayoutBounds());
        logger.info(() -> "[FULLSCREEN_BAR] state=" + state
                + ", host=" + host
                + ", fullScreen=" + stage.isFullScreen()
                + ", stage=" + stage.getX() + "," + stage.getY()
                + " " + stage.getWidth() + "x" + stage.getHeight()
                + ", visible=" + bar.isVisible()
                + ", hover=" + bar.isHover()
                + ", translateY=" + bar.getTranslateY()
                + ", layout=" + bar.getLayoutX() + "," + bar.getLayoutY()
                + " " + bar.getWidth() + "x" + bar.getHeight()
                + ", screenBounds=" + screenBounds);
    }

    private Rectangle2D resolveFullScreenEdgeBounds(Stage stage) {
        if (stage.isFullScreen()) {
            var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                    Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
            if (!screens.isEmpty()) {
                // Screen bounds and Robot mouse positions use the same JavaFX
                // coordinate space. Stage bounds are not reliable in native
                // full-screen mode on every OS/window manager.
                return screens.getFirst().getBounds();
            }
        }
        Scene scene = stage.getScene();
        if (scene != null && scene.getRoot() != null) {
            Bounds bounds = scene.getRoot().localToScreen(scene.getRoot().getBoundsInLocal());
            if (bounds != null && bounds.getWidth() > 0) {
                return new Rectangle2D(bounds.getMinX(), bounds.getMinY(),
                        bounds.getWidth(), Math.max(1, bounds.getHeight()));
            }
        }
        return new Rectangle2D(stage.getX(), stage.getY(),
                Math.max(0, stage.getWidth()), Math.max(0, stage.getHeight()));
    }

    private void stopFullScreenEdgeMonitor() {
        AnimationTimer monitor = fullScreenEdgeMouseMonitor;
        fullScreenEdgeMouseMonitor = null;
        if (monitor != null) monitor.stop();
    }

    static boolean isAtFullScreenTopEdge(double mouseX, double mouseY,
                                         double stageX, double stageY, double stageWidth) {
        return Double.isFinite(mouseX) && Double.isFinite(mouseY)
                && Double.isFinite(stageX) && Double.isFinite(stageY)
                && Double.isFinite(stageWidth) && stageWidth > 0
                && mouseX >= stageX && mouseX < stageX + stageWidth
                && isAtSceneTopEdge(mouseY - stageY);
    }

    static boolean isAtSceneTopEdge(double sceneY) {
        return Double.isFinite(sceneY)
                && sceneY >= 0 && sceneY <= FULL_SCREEN_TOP_EDGE_HEIGHT;
    }

    static boolean isAtRemoteTopEdge(int remoteY, int remoteHeight, double renderedHeight) {
        if (remoteY < 0 || remoteHeight <= 0
                || !Double.isFinite(renderedHeight) || renderedHeight <= 0) {
            return false;
        }
        int remoteTriggerHeight = Math.max(1,
                (int) Math.ceil(FULL_SCREEN_TOP_EDGE_HEIGHT * remoteHeight / renderedHeight));
        return remoteY <= remoteTriggerHeight;
    }

    static double controlBarOffsetForSession(String sessionName, String xrdpSession) {
        String normalized = sessionName == null ? "" : sessionName.trim().toUpperCase();
        return normalized.startsWith("RDP-") || (xrdpSession != null && !xrdpSession.isBlank())
                ? NESTED_CONTROL_BAR_OFFSET : 0;
    }

    private Button createControlButton(String svg, String tooltip, boolean closeButton) {
        SVGPath icon = new SVGPath();
        icon.setContent(svg);
        icon.setFill(Color.WHITE);
        Button button = new Button();
        button.setGraphic(icon);
        button.setFocusTraversable(false);
        button.setTooltip(createTooltip(tooltip));
        String normal = "-fx-background-color: transparent; -fx-background-radius: 0;"
                + " -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0; -fx-cursor: hand;";
        String hover = "-fx-background-color: " + (closeButton ? "#c42b1c" : "rgba(255,255,255,0.22)") + ";"
                + " -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px;"
                + " -fx-padding: 0; -fx-cursor: hand;";
        button.setStyle(normal);
        button.setOnMouseEntered(event -> button.setStyle(hover));
        button.setOnMouseExited(event -> button.setStyle(normal));
        return button;
    }

    private SVGPath createPinIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M14 4V2H10V4L11 5V9L9 11V13H11.25L12 18L12.75 13H15V11L13 9V5Z");
        icon.setFill(Color.WHITE);
        return icon;
    }

    private String controlBarButtonStyle(boolean selected) {
        return "-fx-background-color: " + (selected ? "rgba(255,255,255,0.28)" : "transparent") + ";"
                + " -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 4; -fx-cursor: hand;";
    }

    private Tooltip createTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(200));
        return tooltip;
    }

    private void minimizeFullScreen() {
        Tab tab = ownerTab;
        exitFullScreen();
        Platform.runLater(() -> {
            if (tab != null && tab.getTabPane() != null && tab.getTabPane().getScene() != null
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
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (fullScreenStage != null) alert.initOwner(fullScreenStage);
        alert.setTitle("远程桌面连接");
        alert.setHeaderText(null);
        alert.setContentText("与远程计算机连接的质量非常好。");
        alert.showAndWait();
    }

    public void suppressInitialWindowScrollBars() {
        windowScrollBarsSuppressed = true;
        if (desktopScrollPane != null && fullScreenStage == null) {
            desktopScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            desktopScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        }
    }

    public void enableWindowScrollBars() {
        if (!windowScrollBarsSuppressed) return;
        windowScrollBarsSuppressed = false;
        if (desktopScrollPane != null && fullScreenStage == null) {
            desktopScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            desktopScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }
    }

    private void updateStatus(ConnectionState state) {
        switch (state) {
        case DISCONNECTED -> {
            statusDot.setFill(Color.GRAY);
            stateTextField.setText("未连接");
        }
        case CONNECTING -> {
            statusDot.setFill(Color.ORANGE);
            stateTextField.setText("连接中...");
        }
        case CONNECTED -> {
            statusDot.setFill(Color.GREEN);
            stateTextField.setText("已连接");
        }
        case ERROR -> {
            statusDot.setFill(Color.RED);
            stateTextField.setText("连接失败");
        }
        }
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }
}
