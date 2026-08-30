package com.tangluobo.rdp4j.frontend;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.tangluobo.rdp4j.graphics.Display;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;
import com.tangluobo.rdp4j.graphics.RdpCursor;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.robot.Robot;
import javafx.util.Duration;

/** Pure JavaFX presentation of the protocol's raster backing store. */
public final class FxRdpDisplay implements Display {

    private static final Logger logger = Logger.getLogger(FxRdpDisplay.class.getName());
    private static final String HIDDEN_CURSOR_NAME = "hidden";
    private static final int REMOTE_ECHO_TOLERANCE = 2;
    private static final double MIN_SCREEN_WARP_DISTANCE = 4.0;
    private static final int FRAME_CURSOR_SEARCH_RADIUS = 12;
    private static final double FRAME_CURSOR_MATCH_THRESHOLD = 0.70;
    private static final long FRAME_CURSOR_PROBE_WINDOW_NANOS = 1_000_000_000L;

    private final Object imageLock = new Object();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicBoolean cursorProbePending = new AtomicBoolean();
    private final StackPane view = new StackPane();
    private final ImageView imageView = new ImageView();
    private final PauseTransition cursorExitProbe = new PauseTransition(Duration.millis(450));
    private volatile BufferedImage bufferedImage;
    private volatile BufferedImage displayedImage;
    private volatile PixelBuffer<IntBuffer> pixelBuffer;
    private volatile IndexColorModel colorModel;
    private volatile Runnable firstRemoteUpdateListener;
    private Robot pointerRobot;
    private int lastLocalPointerX = Integer.MIN_VALUE;
    private int lastLocalPointerY = Integer.MIN_VALUE;
    private boolean hasSeenHiddenCursor;
    private String lastCursorMode;
    private String serverCursorMode = "default";
    private Cursor lastVisibleFxCursor = Cursor.DEFAULT;
    private RdpCursor lastVisibleCursorTemplate;
    private boolean frameCursorSuppressed;
    private boolean pointerPositionSuppressed;
    private int frameCursorMisses;
    private long lastLocalPointerMoveNanos;

    public FxRdpDisplay(int width, int height) {
        requireFxThread();
        bufferedImage = createImage(width, height);
        imageView.setFocusTraversable(true);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        view.setStyle("-fx-background-color: black;");
        view.setMinSize(bufferedImage.getWidth(), bufferedImage.getHeight());
        view.setPrefSize(bufferedImage.getWidth(), bufferedImage.getHeight());
        view.getChildren().add(imageView);
        cursorExitProbe.setOnFinished(event -> evaluateFrameCursor(false));
        installImage(bufferedImage);
    }

    public StackPane getView() {
        return view;
    }

    public ImageView getImageView() {
        return imageView;
    }

    public void setScaleToFit(boolean scaleToFit) {
        requireFxThread();
        if (scaleToFit) {
            view.setMinSize(0, 0);
            view.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            imageView.fitWidthProperty().bind(view.widthProperty());
            imageView.fitHeightProperty().bind(view.heightProperty());
        } else {
            imageView.fitWidthProperty().unbind();
            imageView.fitHeightProperty().unbind();
            imageView.setFitWidth(0);
            imageView.setFitHeight(0);
            BufferedImage image = bufferedImage;
            view.setMinSize(image.getWidth(), image.getHeight());
            view.setPrefSize(image.getWidth(), image.getHeight());
        }
    }

    @Override
    public int checkColor(int color) {
        IndexColorModel current = colorModel;
        return current == null ? color : current.getRGB(color);
    }

    @Override
    public RdpCursor createCursor(String name, Point hotspot, Image data) {
        return new RdpCursor(hotspot, name, data);
    }

    @Override
    public Rectangle getBounds() {
        BufferedImage image = bufferedImage;
        return new Rectangle(0, 0, image.getWidth(), image.getHeight());
    }

    @Override
    public BufferedImage getBufferedImage() {
        return bufferedImage;
    }

    @Override
    public Graphics getDisplayGraphics() {
        return bufferedImage.getGraphics();
    }

    @Override
    public int getDisplayHeight() {
        return bufferedImage.getHeight();
    }

    @Override
    public int getDisplayWidth() {
        return bufferedImage.getWidth();
    }

    @Override
    public Point getLocationOnScreen() {
        return new Point(0, 0);
    }

    @Override
    public int getRGB(int x, int y) {
        BufferedImage image = bufferedImage;
        IndexColorModel current = colorModel;
        if (current == null) {
            return image.getRGB(x, y);
        }
        int pixel = image.getRGB(x, y) & 0x00ffffff;
        int[] components = { pixel >>> 16, (pixel >>> 8) & 0xff, pixel & 0xff };
        return current.getDataElement(components, 0);
    }

    @Override
    public int[] getRGB(int x, int y, int width, int height, int[] data, int offset, int scanWidth) {
        return bufferedImage.getRGB(x, y, width, height, data, offset, scanWidth);
    }

    @Override
    public BufferedImage getSubimage(int x, int y, int width, int height) {
        return bufferedImage.getSubimage(x, y, width, height);
    }

    @Override
    public void init(RdesktopCanvas canvas) {
        // The JavaFX input object is installed by FxRdpFrontend.
    }

    @Override
    public void repaint() {
        requestRefresh();
    }

    @Override
    public void repaint(int x, int y, int width, int height) {
        requestRefresh();
    }

    @Override
    public void repaintRemote(int x, int y, int width, int height) {
        requestRefresh();
        requestFrameCursorProbe();
        if (width <= 0 || height <= 0) {
            return;
        }
        Runnable listener = firstRemoteUpdateListener;
        if (listener != null) {
            synchronized (this) {
                if (firstRemoteUpdateListener != listener) {
                    return;
                }
                firstRemoteUpdateListener = null;
            }
            listener.run();
        }
    }

    @Override
    public void setFirstRemoteUpdateListener(Runnable listener) {
        firstRemoteUpdateListener = listener;
    }

    @Override
    public void resizeDisplay(Dimension dimension) {
        BufferedImage replacement = createImage(dimension.width, dimension.height);
        synchronized (imageLock) {
            Graphics2D graphics = replacement.createGraphics();
            try {
                graphics.drawImage(bufferedImage, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            bufferedImage = replacement;
        }
        requestRefresh();
    }

    @Override
    public void setCursor(RdpCursor cursor) {
        Runnable update = () -> {
            if (cursor == null || cursor.getData() == null) {
                serverCursorMode = "default";
                lastVisibleFxCursor = Cursor.DEFAULT;
                lastVisibleCursorTemplate = null;
                frameCursorSuppressed = false;
                pointerPositionSuppressed = false;
                view.setCursor(Cursor.DEFAULT);
                logCursorMode("default");
                return;
            }
            if (HIDDEN_CURSOR_NAME.equals(cursor.getName())
                    || isFullyTransparentCursor(cursor.getData())) {
                // A transparent ImageCursor is rendered as a black rectangle by
                // some Windows/Glass cursor paths and can leave the preceding
                // resize cursor visible. Both the RDP null-system-pointer and a
                // fully transparent VM cursor mean the native cursor is hidden.
                hasSeenHiddenCursor = true;
                serverCursorMode = "hidden";
                frameCursorSuppressed = false;
                pointerPositionSuppressed = false;
                view.setCursor(Cursor.NONE);
                logCursorMode(HIDDEN_CURSOR_NAME.equals(cursor.getName())
                        ? "hidden-system" : "hidden-transparent");
                return;
            }
            int sourceWidth = Math.max(1, cursor.getData().getWidth(null));
            int sourceHeight = Math.max(1, cursor.getData().getHeight(null));
            Dimension2D bestSize = ImageCursor.getBestSize(sourceWidth, sourceHeight);
            int nativeWidth = Math.max(sourceWidth,
                    bestSize.getWidth() > 0 ? (int) Math.round(bestSize.getWidth()) : sourceWidth);
            int nativeHeight = Math.max(sourceHeight,
                    bestSize.getHeight() > 0 ? (int) Math.round(bestSize.getHeight()) : sourceHeight);
            // JavaFX/Windows otherwise scales unsupported sizes (for example
            // 24x24 or the 1x1 hidden pointer) to the native cursor size. A
            // transparent native-size canvas keeps the remote shape 1:1 and
            // prevents the newly allocated area from appearing as a black box.
            WritableImage image = createFxCursorImage(cursor.getData(), nativeWidth, nativeHeight);
            Point hotspot = cursor.getHotspot();
            double x = hotspot == null ? 0 : Math.max(0, Math.min(hotspot.x, sourceWidth - 1));
            double y = hotspot == null ? 0 : Math.max(0, Math.min(hotspot.y, sourceHeight - 1));
            Cursor fxCursor = new ImageCursor(image, x, y);
            serverCursorMode = "custom";
            lastVisibleFxCursor = fxCursor;
            lastVisibleCursorTemplate = cursor;
            frameCursorSuppressed = false;
            pointerPositionSuppressed = false;
            frameCursorMisses = 0;
            view.setCursor(fxCursor);
            logCursorMode("custom-" + sourceWidth + "x" + sourceHeight);
        };
        runOnFxThread(update);
    }

    @Override
    public void movePointer(int x, int y) {
        runOnFxThread(() -> {
            try {
                int remoteX = clamp(x, 0, getDisplayWidth() - 1);
                int remoteY = clamp(y, 0, getDisplayHeight() - 1);
                if (shouldHideForServerPointerPosition(hasSeenHiddenCursor, lastCursorMode)) {
                    // A server pointer-position PDU represents a programmatic
                    // pointer move, not an acknowledgement of ordinary client
                    // movement. Nested VM consoles use even a same-coordinate
                    // or very small move when they recapture the pointer. Apply
                    // the visibility transition before filtering physical Robot
                    // motion, otherwise the restored outer RDP arrow remains on
                    // top of the guest's software cursor after re-entry.
                    pointerPositionSuppressed = true;
                    frameCursorSuppressed = false;
                    view.setCursor(Cursor.NONE);
                    logCursorMode("hidden-recapture-position");
                }
                if (isLocalPointerEcho(remoteX, remoteY,
                        lastLocalPointerX, lastLocalPointerY)) {
                    // Most pointer-position updates only acknowledge the
                    // absolute coordinate just sent by this client. Warping on
                    // those one-pixel echoes pins slow movement to window edges.
                    return;
                }
                Bounds screenBounds = imageView.localToScreen(imageView.getBoundsInLocal());
                if (screenBounds == null || screenBounds.getWidth() <= 0 || screenBounds.getHeight() <= 0) {
                    return;
                }
                if (pointerRobot == null) {
                    pointerRobot = new Robot();
                }
                double remoteWidth = Math.max(1, getDisplayWidth() - 1);
                double remoteHeight = Math.max(1, getDisplayHeight() - 1);
                double screenX = screenBounds.getMinX()
                        + remoteX
                        * Math.max(0, screenBounds.getWidth() - 1) / remoteWidth;
                double screenY = screenBounds.getMinY()
                        + remoteY
                        * Math.max(0, screenBounds.getHeight() - 1) / remoteHeight;
                var currentPosition = pointerRobot.getMousePosition();
                if (!isMeaningfulScreenWarp(currentPosition.getX(), currentPosition.getY(),
                        screenX, screenY)) {
                    // Scaling and integer coordinate round-trips can differ by
                    // a few local pixels. Treat that as an echo as well.
                    return;
                }
                // Do not suppress the JavaFX MouseEvent generated by Robot.
                // Echoing the requested absolute position keeps the RDP server's
                // pointer state synchronized, matching the Swing implementation.
                pointerRobot.mouseMove(screenX, screenY);
            } catch (RuntimeException ignored) {
                // Pointer repositioning is optional on restricted desktops.
            }
        });
    }

    void recordLocalPointerPosition(int x, int y) {
        requireFxThread();
        lastLocalPointerX = clamp(x, 0, getDisplayWidth() - 1);
        lastLocalPointerY = clamp(y, 0, getDisplayHeight() - 1);
        lastLocalPointerMoveNanos = System.nanoTime();
        if (hasSeenHiddenCursor && "custom".equals(serverCursorMode)
                && !pointerPositionSuppressed) {
            cursorExitProbe.playFromStart();
        }
    }

    public static boolean isLocalPointerEcho(int serverX, int serverY,
                                             int lastLocalX, int lastLocalY) {
        return lastLocalX != Integer.MIN_VALUE && lastLocalY != Integer.MIN_VALUE
                && Math.abs((long) serverX - lastLocalX) <= REMOTE_ECHO_TOLERANCE
                && Math.abs((long) serverY - lastLocalY) <= REMOTE_ECHO_TOLERANCE;
    }

    public static boolean isMeaningfulScreenWarp(double currentX, double currentY,
                                                  double targetX, double targetY) {
        double deltaX = targetX - currentX;
        double deltaY = targetY - currentY;
        return deltaX * deltaX + deltaY * deltaY
                > MIN_SCREEN_WARP_DISTANCE * MIN_SCREEN_WARP_DISTANCE;
    }

    public static boolean shouldHideForServerPointerPosition(boolean hasSeenHiddenCursor,
                                                              String currentMode) {
        return hasSeenHiddenCursor
                && (currentMode == null || !currentMode.startsWith("hidden-"));
    }

    private void requestFrameCursorProbe() {
        if (System.nanoTime() - lastLocalPointerMoveNanos > FRAME_CURSOR_PROBE_WINDOW_NANOS
                || !cursorProbePending.compareAndSet(false, true)) {
            return;
        }
        runOnFxThread(() -> {
            cursorProbePending.set(false);
            evaluateFrameCursor(true);
        });
    }

    private void evaluateFrameCursor(boolean remoteFrameArrived) {
        requireFxThread();
        if (!hasSeenHiddenCursor || !"custom".equals(serverCursorMode)
                || pointerPositionSuppressed || lastVisibleCursorTemplate == null
                || lastLocalPointerX == Integer.MIN_VALUE || lastLocalPointerY == Integer.MIN_VALUE) {
            return;
        }
        double score = cursorMatchScore(lastVisibleCursorTemplate.getData(),
                lastVisibleCursorTemplate.getHotspot(), bufferedImage,
                lastLocalPointerX, lastLocalPointerY, FRAME_CURSOR_SEARCH_RADIUS);
        if (score >= FRAME_CURSOR_MATCH_THRESHOLD) {
            frameCursorMisses = 0;
            if (!frameCursorSuppressed) {
                frameCursorSuppressed = true;
                view.setCursor(Cursor.NONE);
                logCursorMode("hidden-frame-cursor");
            }
            return;
        }
        if (!frameCursorSuppressed) {
            return;
        }
        if (remoteFrameArrived && ++frameCursorMisses < 2) {
            return;
        }
        frameCursorSuppressed = false;
        frameCursorMisses = 0;
        view.setCursor(lastVisibleFxCursor);
        logCursorMode("custom-frame-exit");
    }

    public static double cursorMatchScore(Image cursor, Point hotspot, BufferedImage frame,
                                          int pointerX, int pointerY, int searchRadius) {
        if (cursor == null || frame == null) {
            return 0;
        }
        BufferedImage template = toBufferedImage(cursor);
        Point origin = hotspot == null ? new Point() : hotspot;
        int opaquePixels = 0;
        for (int y = 0; y < template.getHeight(); y++) {
            for (int x = 0; x < template.getWidth(); x++) {
                if ((template.getRGB(x, y) >>> 24) >= 224) {
                    opaquePixels++;
                }
            }
        }
        if (opaquePixels < 8) {
            return 0;
        }
        double best = 0;
        int radius = Math.max(0, searchRadius);
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                int startX = pointerX - origin.x + offsetX;
                int startY = pointerY - origin.y + offsetY;
                int compared = 0;
                int matched = 0;
                for (int y = 0; y < template.getHeight(); y++) {
                    int frameY = startY + y;
                    if (frameY < 0 || frameY >= frame.getHeight()) {
                        continue;
                    }
                    for (int x = 0; x < template.getWidth(); x++) {
                        int expected = template.getRGB(x, y);
                        if ((expected >>> 24) < 224) {
                            continue;
                        }
                        int frameX = startX + x;
                        if (frameX < 0 || frameX >= frame.getWidth()) {
                            continue;
                        }
                        compared++;
                        if (colorsNear(expected, frame.getRGB(frameX, frameY), 40)) {
                            matched++;
                        }
                    }
                }
                if (compared >= Math.max(8, opaquePixels / 2)) {
                    best = Math.max(best, (double) matched / compared);
                }
            }
        }
        return best;
    }

    private static boolean colorsNear(int first, int second, int tolerance) {
        return Math.abs(((first >>> 16) & 0xff) - ((second >>> 16) & 0xff)) <= tolerance
                && Math.abs(((first >>> 8) & 0xff) - ((second >>> 8) & 0xff)) <= tolerance
                && Math.abs((first & 0xff) - (second & 0xff)) <= tolerance;
    }

    private static BufferedImage toBufferedImage(Image source) {
        if (source instanceof BufferedImage buffered) {
            return buffered;
        }
        int width = Math.max(1, source.getWidth(null));
        int height = Math.max(1, source.getHeight(null));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void logCursorMode(String mode) {
        if (!mode.equals(lastCursorMode)) {
            lastCursorMode = mode;
            logger.info("FX remote cursor mode=" + mode);
        }
    }

    @Override
    public void setIndexColorModel(IndexColorModel colorModel) {
        this.colorModel = colorModel;
    }

    @Override
    public void setRGB(int x, int y, int color) {
        bufferedImage.setRGB(x, y, opaque(checkColor(color)));
    }

    @Override
    public void setRGB(int x, int y, int width, int height, int[] data, int offset, int scanWidth) {
        IndexColorModel current = colorModel;
        int[] converted = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int color = data[offset + row * scanWidth + column];
                converted[row * width + column] = opaque(current == null ? color : current.getRGB(color));
            }
        }
        bufferedImage.setRGB(x, y, width, height, converted, 0, width);
    }

    @Override
    public void setRGBNoConversion(int x, int y, int width, int height, int[] data, int offset, int scanWidth) {
        int[] opaquePixels = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                opaquePixels[row * width + column] = opaque(data[offset + row * scanWidth + column]);
            }
        }
        bufferedImage.setRGB(x, y, width, height, opaquePixels, 0, width);
    }

    private void requestRefresh() {
        if (!refreshPending.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            refreshPending.set(false);
            BufferedImage current = bufferedImage;
            if (displayedImage != current) {
                installImage(current);
            } else {
                PixelBuffer<IntBuffer> currentBuffer = pixelBuffer;
                if (currentBuffer != null) {
                    currentBuffer.updateBuffer(ignored ->
                            new Rectangle2D(0, 0, current.getWidth(), current.getHeight()));
                }
            }
        });
    }

    private void installImage(BufferedImage image) {
        requireFxThread();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        PixelBuffer<IntBuffer> replacement = new PixelBuffer<>(image.getWidth(), image.getHeight(),
                IntBuffer.wrap(pixels), PixelFormat.getIntArgbPreInstance());
        pixelBuffer = replacement;
        displayedImage = image;
        imageView.setImage(new WritableImage(replacement));
        if (!imageView.fitWidthProperty().isBound()) {
            view.setMinSize(image.getWidth(), image.getHeight());
            view.setPrefSize(image.getWidth(), image.getHeight());
        }
    }

    private static BufferedImage createImage(int width, int height) {
        return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB_PRE);
    }

    private static int opaque(int color) {
        return color | 0xff000000;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static WritableImage createFxImage(Image source) {
        return createFxCursorImage(source, Math.max(1, source.getWidth(null)),
                Math.max(1, source.getHeight(null)));
    }

    public static boolean isFullyTransparentCursor(Image source) {
        if (source == null) {
            return false;
        }
        int width = Math.max(1, source.getWidth(null));
        int height = Math.max(1, source.getHeight(null));
        BufferedImage image;
        if (source instanceof BufferedImage buffered) {
            image = buffered;
        } else {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0) {
                return false;
            }
        }
        return true;
    }

    public static WritableImage createFxCursorImage(Image source, int canvasWidth, int canvasHeight) {
        int width = Math.max(1, source.getWidth(null));
        int height = Math.max(1, source.getHeight(null));
        if (canvasWidth < width || canvasHeight < height) {
            throw new IllegalArgumentException("Cursor canvas must contain the source image without scaling");
        }
        BufferedImage image;
        if (source instanceof BufferedImage buffered) {
            image = buffered;
        } else {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        int[] sourcePixels = image.getRGB(0, 0, width, height, null, 0, width);
        int[] nativePixels = new int[canvasWidth * canvasHeight];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = sourcePixels[y * width + x];
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    // Glass expects transparent cursor padding to contain no
                    // residual RGB; some Windows cursor paths render it black.
                    nativePixels[y * canvasWidth + x] = 0;
                    continue;
                }
                int red = ((argb >>> 16) & 0xff) * alpha / 255;
                int green = ((argb >>> 8) & 0xff) * alpha / 255;
                int blue = (argb & 0xff) * alpha / 255;
                nativePixels[y * canvasWidth + x] = (alpha << 24)
                        | (red << 16) | (green << 8) | blue;
            }
        }
        PixelBuffer<IntBuffer> cursorBuffer = new PixelBuffer<>(canvasWidth, canvasHeight,
                IntBuffer.wrap(nativePixels), PixelFormat.getIntArgbPreInstance());
        return new WritableImage(cursorBuffer);
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("JavaFX display must be created on the JavaFX application thread");
        }
    }

}
