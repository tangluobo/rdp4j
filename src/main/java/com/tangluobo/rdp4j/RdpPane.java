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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
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
    private static final double FULL_SCREEN_TOP_EDGE_HEIGHT = 5;
    private static final double NESTED_CONTROL_BAR_OFFSET = 44;
    private static final long EDGE_POLL_INTERVAL_NANOS = 50_000_000L;

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
    private boolean fullScreenTransitioning;
    private double exitBarShownY;
    private ScrollPane.ScrollBarPolicy verticalPolicyBeforeFullScreen = ScrollPane.ScrollBarPolicy.AS_NEEDED;
    private ScrollPane.ScrollBarPolicy horizontalPolicyBeforeFullScreen = ScrollPane.ScrollBarPolicy.AS_NEEDED;

    private String host;
    private int port;
    private int screenWidth;
    private int screenHeight;
    private int colorDepth;
    private volatile KeyCombination fullScreenKeys = KeyCombination.valueOf("Ctrl+Shift+Enter");

    public RdpPane() {
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
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (event.getSceneY() <= FULL_SCREEN_TOP_EDGE_HEIGHT) {
                showExitBar();
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
            showExitBar();
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
        bar.setOnMouseEntered(event -> {
            if (hideExitBarTimer != null) hideExitBarTimer.stop();
        });
        bar.setOnMouseExited(event -> {
            if (!exitBarPinned && hideExitBarTimer != null) hideExitBarTimer.playFromStart();
        });
        hideExitBarTimer = new PauseTransition(Duration.seconds(5));
        hideExitBarTimer.setOnFinished(event -> hideExitBar());
        return bar;
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
        if (exitBar == null) {
            return;
        }
        if (exitBarSlide != null) exitBarSlide.stop();
        exitBar.setVisible(true);
        exitBar.setTranslateY(exitBarShownY);
        if (!exitBarPinned && hideExitBarTimer != null && !exitBar.isHover()) {
            hideExitBarTimer.playFromStart();
        }
    }

    private void hideExitBar() {
        if (exitBarPinned || exitBar == null || !exitBar.isVisible()) {
            return;
        }
        if (exitBarSlide != null) exitBarSlide.stop();
        exitBarSlide = new TranslateTransition(Duration.millis(180), exitBar);
        exitBarSlide.setToY(-(exitBar.getHeight() + 8));
        exitBarSlide.setOnFinished(event -> {
            if (exitBar != null) exitBar.setVisible(false);
        });
        exitBarSlide.play();
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
                if (isAtFullScreenTopEdge(mouse.getX(), mouse.getY(),
                        stage.getX(), stage.getY(), stage.getWidth())) {
                    showExitBar();
                }
            }
        };
        fullScreenEdgeMouseMonitor.start();
    }

    private void stopFullScreenEdgeMonitor() {
        AnimationTimer monitor = fullScreenEdgeMouseMonitor;
        fullScreenEdgeMouseMonitor = null;
        if (monitor != null) monitor.stop();
    }

    static boolean isAtFullScreenTopEdge(double mouseX, double mouseY,
                                         double stageX, double stageY, double stageWidth) {
        return stageWidth > 0 && mouseX >= stageX && mouseX < stageX + stageWidth
                && mouseY >= stageY && mouseY <= stageY + FULL_SCREEN_TOP_EDGE_HEIGHT;
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
