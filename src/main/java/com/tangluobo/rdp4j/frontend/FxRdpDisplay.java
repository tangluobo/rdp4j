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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import com.tangluobo.rdp4j.graphics.Display;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;
import com.tangluobo.rdp4j.graphics.RdpCursor;

import javafx.application.Platform;
import javafx.animation.AnimationTimer;
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

/** Pure JavaFX presentation of the protocol's raster backing store. */
public final class FxRdpDisplay implements Display {

    private static final Logger logger = Logger.getLogger(FxRdpDisplay.class.getName());
    private static final String HIDDEN_CURSOR_NAME = "hidden";
    private static final int REMOTE_ECHO_TOLERANCE = 2;
    private static final double MIN_SCREEN_WARP_DISTANCE = 4.0;
    private static final int LEGACY_VM_CURSOR_MAX_SIZE = 24;
    private static final int MOVEMENT_PROBE_RADIUS = 24;
    private static final int MOVEMENT_EVIDENCE_THRESHOLD = 3;
    private static final long MOVEMENT_PROBE_INTERVAL_NANOS = 40_000_000L;
    private static final long MOVEMENT_PROBE_TIMEOUT_NANOS = 400_000_000L;

    private final Object imageLock = new Object();
    private final Object refreshLock = new Object();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicBoolean cursorProbePending = new AtomicBoolean();
    private final StackPane view = new StackPane();
    private final ImageView imageView = new ImageView();
    private final BiConsumer<Integer, Integer> serverPointerMovedListener;
    private final Deque<MovementProbe> movementProbes = new ArrayDeque<>();
    private final AnimationTimer movementProbeTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            expireMovementProbes(now);
            if (movementProbes.isEmpty()) {
                stop();
            }
        }
    };
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
    private boolean softwareCursorProbeEligible;
    private boolean frameCursorSuppressed;
    private boolean pointerPositionSuppressed;
    private int movementEvidence;
    private int lastProbePointerX = Integer.MIN_VALUE;
    private int lastProbePointerY = Integer.MIN_VALUE;
    private long lastMovementProbeNanos;
    private BufferedImage dirtyImage;
    private int dirtyLeft = Integer.MAX_VALUE;
    private int dirtyTop = Integer.MAX_VALUE;
    private int dirtyRight = -1;
    private int dirtyBottom = -1;

    public FxRdpDisplay(int width, int height) {
        this(width, height, null);
    }

    public FxRdpDisplay(int width, int height,
                        BiConsumer<Integer, Integer> serverPointerMovedListener) {
        requireFxThread();
        this.serverPointerMovedListener = serverPointerMovedListener == null
                ? (x, y) -> { } : serverPointerMovedListener;
        bufferedImage = createImage(width, height);
        imageView.setFocusTraversable(true);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        view.setStyle("-fx-background-color: black;");
        view.setMinSize(bufferedImage.getWidth(), bufferedImage.getHeight());
        view.setPrefSize(bufferedImage.getWidth(), bufferedImage.getHeight());
        view.getChildren().add(imageView);
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
        requestRefresh(x, y, width, height);
    }

    @Override
    public void repaintRemote(int x, int y, int width, int height) {
        requestRefresh(x, y, width, height);
        requestMovementCorrelationCheck();
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
                softwareCursorProbeEligible = false;
                frameCursorSuppressed = false;
                pointerPositionSuppressed = false;
                resetMovementCorrelation();
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
                softwareCursorProbeEligible = false;
                frameCursorSuppressed = false;
                pointerPositionSuppressed = false;
                resetMovementCorrelation();
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
            softwareCursorProbeEligible = isLegacyVmSoftwareCursorCandidate(
                    sourceWidth, sourceHeight);
            pointerPositionSuppressed = false;
            if (!softwareCursorProbeEligible) {
                // The observed nested Linux console uses a legacy 24-pixel
                // software pointer. Normal Windows cursors in this stream are
                // 32-pixel protocol cursors; frame-change inference on those
                // mistakes file highlights and context menus for a second
                // cursor and hides the only visible pointer.
                frameCursorSuppressed = false;
                resetMovementCorrelation();
            }
            if (softwareCursorProbeEligible && frameCursorSuppressed
                    && movementEvidence >= MOVEMENT_EVIDENCE_THRESHOLD) {
                view.setCursor(Cursor.NONE);
                logCursorMode("hidden-software-cursor");
            } else {
                frameCursorSuppressed = false;
                view.setCursor(fxCursor);
                logCursorMode("custom-" + sourceWidth + "x" + sourceHeight);
            }
        };
        runOnFxThread(update);
    }

    @Override
    public void movePointer(int x, int y) {
        runOnFxThread(() -> {
            try {
                int remoteX = clamp(x, 0, getDisplayWidth() - 1);
                int remoteY = clamp(y, 0, getDisplayHeight() - 1);
                serverPointerMovedListener.accept(remoteX, remoteY);
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
        int previousX = lastLocalPointerX;
        int previousY = lastLocalPointerY;
        lastLocalPointerX = clamp(x, 0, getDisplayWidth() - 1);
        lastLocalPointerY = clamp(y, 0, getDisplayHeight() - 1);
        long now = System.nanoTime();
        if (!softwareCursorProbeEligible || !"custom".equals(serverCursorMode)
                || pointerPositionSuppressed
                || previousX == Integer.MIN_VALUE || previousY == Integer.MIN_VALUE
                || now - lastMovementProbeNanos < MOVEMENT_PROBE_INTERVAL_NANOS
                || squaredDistance(lastProbePointerX, lastProbePointerY,
                        lastLocalPointerX, lastLocalPointerY) < 9) {
            return;
        }
        MovementProbe probe = new MovementProbe(now,
                FramePatch.capture(bufferedImage, previousX, previousY, MOVEMENT_PROBE_RADIUS),
                FramePatch.capture(bufferedImage, lastLocalPointerX, lastLocalPointerY,
                        MOVEMENT_PROBE_RADIUS));
        while (movementProbes.size() >= 8) {
            movementProbes.removeFirst();
        }
        movementProbes.addLast(probe);
        lastProbePointerX = lastLocalPointerX;
        lastProbePointerY = lastLocalPointerY;
        lastMovementProbeNanos = now;
        movementProbeTimer.start();
    }

    void recordLocalPointerButtonPosition(int x, int y) {
        requireFxThread();
        lastLocalPointerX = clamp(x, 0, getDisplayWidth() - 1);
        lastLocalPointerY = clamp(y, 0, getDisplayHeight() - 1);
        resetMovementCorrelation();
        if (frameCursorSuppressed && "custom".equals(serverCursorMode)
                && !pointerPositionSuppressed) {
            frameCursorSuppressed = false;
            view.setCursor(lastVisibleFxCursor);
            logCursorMode("custom-pointer-button");
        }
    }

    public static boolean isLegacyVmSoftwareCursorCandidate(int width, int height) {
        return width > 0 && height > 0
                && width <= LEGACY_VM_CURSOR_MAX_SIZE
                && height <= LEGACY_VM_CURSOR_MAX_SIZE;
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

    private void requestMovementCorrelationCheck() {
        if (!cursorProbePending.compareAndSet(false, true)) {
            return;
        }
        runOnFxThread(() -> {
            cursorProbePending.set(false);
            evaluateMovementCorrelation();
        });
    }

    private void evaluateMovementCorrelation() {
        requireFxThread();
        if (!softwareCursorProbeEligible || !"custom".equals(serverCursorMode)
                || pointerPositionSuppressed
                || movementProbes.isEmpty()) {
            return;
        }
        MovementProbe matched = null;
        for (var iterator = movementProbes.descendingIterator(); iterator.hasNext();) {
            MovementProbe candidate = iterator.next();
            if (candidate.matches(bufferedImage)) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            return;
        }
        while (!movementProbes.isEmpty()) {
            MovementProbe removed = movementProbes.removeFirst();
            if (removed == matched) {
                break;
            }
        }
        movementEvidence = Math.min(MOVEMENT_EVIDENCE_THRESHOLD + 2, movementEvidence + 1);
        if (movementEvidence >= MOVEMENT_EVIDENCE_THRESHOLD && !frameCursorSuppressed) {
            frameCursorSuppressed = true;
            view.setCursor(Cursor.NONE);
            logCursorMode("hidden-software-cursor");
        }
    }

    private void expireMovementProbes(long now) {
        int misses = 0;
        while (!movementProbes.isEmpty()
                && now - movementProbes.peekFirst().createdNanos() >= MOVEMENT_PROBE_TIMEOUT_NANOS) {
            movementProbes.removeFirst();
            misses++;
        }
        if (misses == 0) {
            return;
        }
        movementEvidence = Math.max(0, movementEvidence - misses);
        if (frameCursorSuppressed && movementEvidence == 0
                && "custom".equals(serverCursorMode) && !pointerPositionSuppressed) {
            frameCursorSuppressed = false;
            view.setCursor(lastVisibleFxCursor);
            logCursorMode("custom-hardware-cursor");
        }
    }

    private void resetMovementCorrelation() {
        movementProbes.clear();
        movementProbeTimer.stop();
        movementEvidence = 0;
        lastProbePointerX = Integer.MIN_VALUE;
        lastProbePointerY = Integer.MIN_VALUE;
        lastMovementProbeNanos = 0;
    }

    private static long squaredDistance(int firstX, int firstY, int secondX, int secondY) {
        if (firstX == Integer.MIN_VALUE || firstY == Integer.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        long deltaX = (long) secondX - firstX;
        long deltaY = (long) secondY - firstY;
        return deltaX * deltaX + deltaY * deltaY;
    }

    public static boolean resemblesSoftwareCursorMovement(int oldChanged, int oldPixels,
                                                           int newChanged, int newPixels) {
        return isCursorSizedChange(oldChanged, oldPixels)
                && isCursorSizedChange(newChanged, newPixels);
    }

    private static boolean isCursorSizedChange(int changed, int pixels) {
        return pixels > 0 && changed >= 6 && changed <= Math.max(24, pixels * 45 / 100);
    }

    private record MovementProbe(long createdNanos, FramePatch oldPosition, FramePatch newPosition) {
        boolean matches(BufferedImage frame) {
            int oldChanged = oldPosition.changedPixels(frame);
            int newChanged = newPosition.changedPixels(frame);
            return resemblesSoftwareCursorMovement(oldChanged, oldPosition.pixelCount(),
                    newChanged, newPosition.pixelCount());
        }
    }

    private record FramePatch(int x, int y, int width, int height, int[] pixels) {
        static FramePatch capture(BufferedImage frame, int centerX, int centerY, int radius) {
            int x = clamp(centerX - radius, 0, frame.getWidth() - 1);
            int y = clamp(centerY - radius, 0, frame.getHeight() - 1);
            int right = clamp(centerX + radius, 0, frame.getWidth() - 1);
            int bottom = clamp(centerY + radius, 0, frame.getHeight() - 1);
            int width = right - x + 1;
            int height = bottom - y + 1;
            return new FramePatch(x, y, width, height,
                    frame.getRGB(x, y, width, height, null, 0, width));
        }

        int pixelCount() {
            return pixels.length;
        }

        int changedPixels(BufferedImage frame) {
            int[] current = frame.getRGB(x, y, width, height, null, 0, width);
            int changed = 0;
            for (int i = 0; i < pixels.length; i++) {
                if ((pixels[i] & 0x00ffffff) != (current[i] & 0x00ffffff)) {
                    changed++;
                }
            }
            return changed;
        }
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
        BufferedImage image = bufferedImage;
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (current == null) {
            copyOpaquePixels(target, image.getWidth(), x, y,
                    data, offset, scanWidth, width, height);
            return;
        }
        for (int row = 0; row < height; row++) {
            int sourceIndex = offset + row * scanWidth;
            int targetIndex = (y + row) * image.getWidth() + x;
            for (int column = 0; column < width; column++) {
                target[targetIndex + column] = opaque(current.getRGB(data[sourceIndex + column]));
            }
        }
    }

    @Override
    public void setRGBNoConversion(int x, int y, int width, int height, int[] data, int offset, int scanWidth) {
        BufferedImage image = bufferedImage;
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        copyOpaquePixels(target, image.getWidth(), x, y,
                data, offset, scanWidth, width, height);
    }

    private void requestRefresh() {
        BufferedImage image = bufferedImage;
        requestRefresh(0, 0, image.getWidth(), image.getHeight());
    }

    private void requestRefresh(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        BufferedImage image = bufferedImage;
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int left = clamp(x, 0, imageWidth);
        int top = clamp(y, 0, imageHeight);
        int right = (int) Math.max(0, Math.min((long) imageWidth, (long) x + width));
        int bottom = (int) Math.max(0, Math.min((long) imageHeight, (long) y + height));
        if (right <= left || bottom <= top) {
            return;
        }

        boolean schedule;
        synchronized (refreshLock) {
            if (dirtyImage != image) {
                dirtyImage = image;
                clearDirtyRegion();
            }
            dirtyLeft = Math.min(dirtyLeft, left);
            dirtyTop = Math.min(dirtyTop, top);
            dirtyRight = Math.max(dirtyRight, right);
            dirtyBottom = Math.max(dirtyBottom, bottom);
            schedule = refreshPending.compareAndSet(false, true);
        }
        if (schedule) {
            Platform.runLater(this::flushRefresh);
        }
    }

    private void flushRefresh() {
        BufferedImage current;
        Rectangle2D dirtyRegion;
        synchronized (refreshLock) {
            current = bufferedImage;
            if (dirtyImage != current) {
                dirtyImage = current;
                dirtyLeft = 0;
                dirtyTop = 0;
                dirtyRight = current.getWidth();
                dirtyBottom = current.getHeight();
            }
            dirtyRegion = dirtyRight > dirtyLeft && dirtyBottom > dirtyTop
                    ? new Rectangle2D(dirtyLeft, dirtyTop,
                            dirtyRight - dirtyLeft, dirtyBottom - dirtyTop)
                    : null;
            clearDirtyRegion();
            refreshPending.set(false);
        }
        if (displayedImage != current) {
            installImage(current);
        } else if (dirtyRegion != null) {
            PixelBuffer<IntBuffer> currentBuffer = pixelBuffer;
            if (currentBuffer != null) {
                currentBuffer.updateBuffer(ignored -> dirtyRegion);
            }
        }
    }

    private void clearDirtyRegion() {
        dirtyLeft = Integer.MAX_VALUE;
        dirtyTop = Integer.MAX_VALUE;
        dirtyRight = -1;
        dirtyBottom = -1;
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

    static void copyOpaquePixels(int[] target, int targetStride, int targetX, int targetY,
                                 int[] source, int sourceOffset, int sourceStride,
                                 int width, int height) {
        for (int row = 0; row < height; row++) {
            int sourceIndex = sourceOffset + row * sourceStride;
            int targetIndex = (targetY + row) * targetStride + targetX;
            for (int column = 0; column < width; column++) {
                target[targetIndex + column] = opaque(source[sourceIndex + column]);
            }
        }
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
