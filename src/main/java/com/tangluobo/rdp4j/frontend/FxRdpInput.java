package com.tangluobo.rdp4j.frontend;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;

import com.tangluobo.rdp4j.Input;
import com.tangluobo.rdp4j.RdpInput;
import com.tangluobo.rdp4j.State;
import com.sun.jna.Library;
import com.sun.jna.Native;

import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.image.ImageView;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;

/** Sends JavaFX key and pointer events directly as RDP input PDUs. */
public final class FxRdpInput implements RdpInput {

    private static final int SCANCODE_EXTENDED = 0x80;
    private static final int KBD_FLAG_EXT = 0x0100;
    private static final int KBD_FLAG_UP = 0x8000;
    private static final int KBD_FLAG_DOWN = 0x4000;
    private static final int RDP_KEY_RELEASE = KBD_FLAG_DOWN | KBD_FLAG_UP;
    private static final int RDP_INPUT_SCANCODE = 4;
    private static final int RDP_INPUT_SYNCHRONIZE = 0;
    private static final int RDP_INPUT_MOUSE = 0x8001;
    private static final int TS_SYNC_SCROLL_LOCK = 0x0001;
    private static final int TS_SYNC_NUM_LOCK = 0x0002;
    private static final int TS_SYNC_CAPS_LOCK = 0x0004;
    private static final int VK_CAPITAL = 0x14;
    private static final int VK_NUMLOCK = 0x90;
    private static final int VK_SCROLL = 0x91;
    private static final int MOUSE_FLAG_BUTTON1 = 0x1000;
    private static final int MOUSE_FLAG_BUTTON2 = 0x2000;
    private static final int MOUSE_FLAG_BUTTON3 = 0x4000;
    private static final int MOUSE_FLAG_WHEEL_UP = 0x0280;
    private static final int MOUSE_FLAG_WHEEL_DOWN = 0x0380;
    private static final int MOUSE_FLAG_DOWN = 0x8000;
    private static final int MOUSE_FLAG_MOVE = 0x0800;

    private final State state;
    private final FxRdpDisplay display;
    private final ImageView target;
    private final StackPane mouseTarget;
    private final BiConsumer<Integer, Integer> pointerMovedListener;
    private final Runnable focusGainedListener;
    private final Set<KeyCode> pressedKeys = EnumSet.noneOf(KeyCode.class);
    private final EventHandler<InputMethodEvent> inputMethodFilter = event -> event.consume();
    private final ChangeListener<Boolean> focusListener;
    private boolean capsLock;
    private boolean numLock = true;
    private boolean scrollLock;
    private boolean keypadLockSynchronizationPending = true;

    public FxRdpInput(State state, FxRdpDisplay display) {
        this(state, display, null);
    }

    public FxRdpInput(State state, FxRdpDisplay display,
                      BiConsumer<Integer, Integer> pointerMovedListener) {
        this(state, display, pointerMovedListener, null);
    }

    FxRdpInput(State state, FxRdpDisplay display,
               BiConsumer<Integer, Integer> pointerMovedListener,
               Runnable focusGainedListener) {
        this.state = state;
        this.display = display;
        this.target = display.getImageView();
        this.mouseTarget = display.getView();
        this.pointerMovedListener = pointerMovedListener == null ? (x, y) -> { } : pointerMovedListener;
        this.focusGainedListener = focusGainedListener == null ? () -> { } : focusGainedListener;
        this.focusListener = (observable, oldValue, focused) -> {
            if (focused) {
                gainedFocus();
                this.focusGainedListener.run();
            } else {
                lostFocus();
            }
        };
        installHandlers();
    }

    private void installHandlers() {
        target.setOnKeyPressed(this::keyPressed);
        target.setOnKeyReleased(this::keyReleased);
        target.setOnKeyTyped(KeyEvent::consume);
        target.addEventFilter(InputMethodEvent.ANY, inputMethodFilter);
        mouseTarget.setPickOnBounds(true);
        mouseTarget.setOnMousePressed(this::mousePressed);
        mouseTarget.setOnMouseReleased(this::mouseReleased);
        mouseTarget.setOnMouseMoved(this::mouseMoved);
        mouseTarget.setOnMouseDragged(this::mouseMoved);
        mouseTarget.setOnScroll(this::mouseScrolled);
        target.focusedProperty().addListener(focusListener);
    }

    /**
     * Dispatches a Scene-level key event through the same RDP path used by the
     * focused desktop image. Full-screen containers use this before JavaFX can
     * interpret combinations such as Ctrl+Tab as local focus traversal.
     */
    public void forwardKeyEvent(KeyEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            keyPressed(event);
        } else if (event.getEventType() == KeyEvent.KEY_RELEASED) {
            keyReleased(event);
        } else {
            // Physical press/release events carry the scan codes. Consuming
            // KEY_TYPED prevents local controls from processing the character.
            event.consume();
        }
    }

    /** Sends a scan code captured before JavaFX receives the native key. */
    public void forwardNativeKey(int scanCode, boolean extended, boolean release) {
        int normalized = normalizeNativeScanCode(scanCode, extended);
        if (normalized >= 0) {
            sendScancode(normalized, release);
        }
    }

    public static int normalizeNativeScanCode(int scanCode, boolean extended) {
        if (scanCode <= 0 || scanCode > 0xff) {
            return -1;
        }
        return extended ? scanCode | SCANCODE_EXTENDED : scanCode;
    }

    private void keyPressed(KeyEvent event) {
        int scancode = scancodeFor(event.getCode());
        if (scancode < 0) {
            return;
        }
        if (keypadLockSynchronizationPending && isKeypadLockDependent(event.getCode())) {
            // Activation/redirection can discard an earlier TS_SYNC_EVENT.
            // Re-send the absolute local lock state immediately before the
            // first keypad digit so it cannot be interpreted as navigation.
            synchronizeLockKeys();
            keypadLockSynchronizationPending = false;
        }
        pressedKeys.add(event.getCode());
        sendScancode(scancode, false);
        event.consume();
    }

    private void keyReleased(KeyEvent event) {
        int scancode = scancodeFor(event.getCode());
        if (scancode < 0) {
            return;
        }
        sendScancode(scancode, true);
        pressedKeys.remove(event.getCode());
        switch (event.getCode()) {
        case CAPS, NUM_LOCK, SCROLL_LOCK -> refreshLocalLockKeyState();
        default -> { }
        }
        event.consume();
    }

    private void mousePressed(MouseEvent event) {
        target.requestFocus();
        int flag = mouseButtonFlag(event.getButton());
        if (flag != 0) {
            int[] point = toRemotePoint(event.getSceneX(), event.getSceneY());
            sendMouse(flag | MOUSE_FLAG_DOWN, point[0], point[1]);
            event.consume();
        }
    }

    private void mouseReleased(MouseEvent event) {
        int flag = mouseButtonFlag(event.getButton());
        if (flag != 0) {
            int[] point = toRemotePoint(event.getSceneX(), event.getSceneY());
            sendMouse(flag, point[0], point[1]);
            event.consume();
        }
    }

    private void mouseMoved(MouseEvent event) {
        int[] point = toRemotePoint(event.getSceneX(), event.getSceneY());
        // Notify the container before the remote server can acknowledge or
        // reposition this pointer. This path is independent of the cursor
        // protocol used by the connected Windows/Linux version.
        pointerMovedListener.accept(point[0], point[1]);
        // Relative mouse mode in nested VM consoles depends on the ordering of
        // each absolute move and the following server-requested pointer warp.
        // Delaying moves until the next FX pulse can send a stale edge position
        // after the warp and pin the cursor on a resize border.
        sendMouse(MOUSE_FLAG_MOVE, point[0], point[1]);
        event.consume();
    }

    private void mouseScrolled(ScrollEvent event) {
        int flag = event.getDeltaY() >= 0 ? MOUSE_FLAG_WHEEL_UP : MOUSE_FLAG_WHEEL_DOWN;
        int[] point = toRemotePoint(event.getSceneX(), event.getSceneY());
        sendMouse(flag | MOUSE_FLAG_DOWN, point[0], point[1]);
        event.consume();
    }

    private int mouseButtonFlag(MouseButton button) {
        return switch (button) {
        case PRIMARY -> MOUSE_FLAG_BUTTON1;
        case SECONDARY -> MOUSE_FLAG_BUTTON2;
        case MIDDLE -> MOUSE_FLAG_BUTTON3;
        default -> 0;
        };
    }

    private int[] toRemotePoint(double sceneX, double sceneY) {
        Point2D local = mouseTarget.sceneToLocal(sceneX, sceneY);
        Bounds imageBounds = target.getBoundsInParent();
        int x = mapRemoteCoordinate(local.getX(), imageBounds.getMinX(),
                imageBounds.getWidth(), display.getDisplayWidth());
        int y = mapRemoteCoordinate(local.getY(), imageBounds.getMinY(),
                imageBounds.getHeight(), display.getDisplayHeight());
        return new int[] { x, y };
    }

    public static int mapRemoteCoordinate(double pointer, double imageOffset,
                                          double renderedSize, int remoteSize) {
        if (remoteSize <= 1 || renderedSize <= 0 || !Double.isFinite(pointer)) {
            return 0;
        }
        double normalized = (pointer - imageOffset) / renderedSize;
        return clamp((int) Math.floor(normalized * remoteSize), 0, remoteSize - 1);
    }

    private void sendMouse(int flags, int x, int y) {
        if ((flags & MOUSE_FLAG_MOVE) != 0) {
            display.recordLocalPointerPosition(x, y);
        } else {
            // A click can repaint a menu or selection around the pointer. Do
            // not let stale movement probes classify that UI repaint as a
            // software cursor and hide the real JavaFX cursor.
            display.recordLocalPointerButtonPosition(x, y);
        }
        if (state.getRdp() == null) {
            return;
        }
        state.getRdp().sendInput(Input.getTime(), RDP_INPUT_MOUSE, flags, x, y);
    }

    private void sendScancode(int scancode, boolean release) {
        if (state.getRdp() == null) {
            return;
        }
        int flags = release ? RDP_KEY_RELEASE : 0;
        if ((scancode & SCANCODE_EXTENDED) != 0) {
            flags |= KBD_FLAG_EXT;
            scancode &= ~SCANCODE_EXTENDED;
        }
        state.getRdp().sendInput(Input.getTime(), RDP_INPUT_SCANCODE, flags, scancode, 0);
    }

    @Override
    public void gainedFocus() {
        synchronizeLockKeys();
        keypadLockSynchronizationPending = true;
    }

    @Override
    public void lostFocus() {
        if (state.getRdp() != null) {
            for (KeyCode code : pressedKeys.toArray(KeyCode[]::new)) {
                int scancode = scancodeFor(code);
                if (scancode >= 0) {
                    sendScancode(scancode, true);
                }
            }
            // Clear modifiers even if their JavaFX release event was lost during a Scene move.
            sendScancode(0x2a, true);
            sendScancode(0x36, true);
            sendScancode(0x1d, true);
            sendScancode(0x1d | SCANCODE_EXTENDED, true);
            sendScancode(0x38, true);
            sendScancode(0x38 | SCANCODE_EXTENDED, true);
        }
        pressedKeys.clear();
    }

    @Override
    public void triggerReadyToSend() {
        synchronizeLockKeys();
        keypadLockSynchronizationPending = true;
    }

    @Override
    public void synchronizeLockKeys() {
        if (state.getRdp() == null) {
            return;
        }
        refreshLocalLockKeyState();
        int flags = toggleFlags(capsLock, numLock, scrollLock);
        state.getRdp().sendInput(Input.getTime(), RDP_INPUT_SYNCHRONIZE, 0, flags, 0);
    }

    @Override
    public void sendCtrlAltDel() {
        sendScancode(0x1d, false);
        sendScancode(0x38, false);
        sendScancode(0x53 | SCANCODE_EXTENDED, false);
        sendScancode(0x53 | SCANCODE_EXTENDED, true);
        sendScancode(0x38, true);
        sendScancode(0x1d, true);
    }

    @Override
    public void dispose() {
        lostFocus();
        target.setOnKeyPressed(null);
        target.setOnKeyReleased(null);
        target.setOnKeyTyped(null);
        target.removeEventFilter(InputMethodEvent.ANY, inputMethodFilter);
        mouseTarget.setOnMousePressed(null);
        mouseTarget.setOnMouseReleased(null);
        mouseTarget.setOnMouseMoved(null);
        mouseTarget.setOnMouseDragged(null);
        mouseTarget.setOnScroll(null);
        target.focusedProperty().removeListener(focusListener);
    }

    /** Windows Set-1 scan code; bit 7 marks an E0-prefixed extended key. */
    public static int scancodeFor(KeyCode code) {
        if (code == null) {
            return -1;
        }
        return switch (code) {
        case ESCAPE -> 0x01;
        case DIGIT1 -> 0x02; case DIGIT2 -> 0x03; case DIGIT3 -> 0x04;
        case DIGIT4 -> 0x05; case DIGIT5 -> 0x06; case DIGIT6 -> 0x07;
        case DIGIT7 -> 0x08; case DIGIT8 -> 0x09; case DIGIT9 -> 0x0a;
        case DIGIT0 -> 0x0b; case MINUS -> 0x0c; case EQUALS -> 0x0d;
        case BACK_SPACE -> 0x0e; case TAB -> 0x0f;
        case Q -> 0x10; case W -> 0x11; case E -> 0x12; case R -> 0x13;
        case T -> 0x14; case Y -> 0x15; case U -> 0x16; case I -> 0x17;
        case O -> 0x18; case P -> 0x19; case OPEN_BRACKET -> 0x1a;
        case CLOSE_BRACKET -> 0x1b; case ENTER -> 0x1c; case CONTROL -> 0x1d;
        case A -> 0x1e; case S -> 0x1f; case D -> 0x20; case F -> 0x21;
        case G -> 0x22; case H -> 0x23; case J -> 0x24; case K -> 0x25;
        case L -> 0x26; case SEMICOLON -> 0x27; case QUOTE -> 0x28;
        case BACK_QUOTE -> 0x29; case SHIFT -> 0x2a; case BACK_SLASH -> 0x2b;
        case Z -> 0x2c; case X -> 0x2d; case C -> 0x2e; case V -> 0x2f;
        case B -> 0x30; case N -> 0x31; case M -> 0x32; case COMMA -> 0x33;
        case PERIOD -> 0x34; case SLASH -> 0x35; case ALT -> 0x38;
        case SPACE -> 0x39; case CAPS -> 0x3a;
        case F1 -> 0x3b; case F2 -> 0x3c; case F3 -> 0x3d; case F4 -> 0x3e;
        case F5 -> 0x3f; case F6 -> 0x40; case F7 -> 0x41; case F8 -> 0x42;
        case F9 -> 0x43; case F10 -> 0x44; case NUM_LOCK -> 0x45;
        case SCROLL_LOCK -> 0x46; case F11 -> 0x57; case F12 -> 0x58;
        case NUMPAD7 -> 0x47; case NUMPAD8, KP_UP -> 0x48; case NUMPAD9 -> 0x49;
        case SUBTRACT -> 0x4a; case NUMPAD4, KP_LEFT -> 0x4b; case NUMPAD5 -> 0x4c;
        case NUMPAD6, KP_RIGHT -> 0x4d; case ADD -> 0x4e; case NUMPAD1 -> 0x4f;
        case NUMPAD2, KP_DOWN -> 0x50; case NUMPAD3 -> 0x51; case NUMPAD0 -> 0x52;
        case DECIMAL, SEPARATOR -> 0x53; case MULTIPLY -> 0x37;
        case DIVIDE -> 0x35 | SCANCODE_EXTENDED;
        case HOME -> 0x47 | SCANCODE_EXTENDED; case UP -> 0x48 | SCANCODE_EXTENDED;
        case PAGE_UP -> 0x49 | SCANCODE_EXTENDED; case LEFT -> 0x4b | SCANCODE_EXTENDED;
        case RIGHT -> 0x4d | SCANCODE_EXTENDED; case END -> 0x4f | SCANCODE_EXTENDED;
        case DOWN -> 0x50 | SCANCODE_EXTENDED; case PAGE_DOWN -> 0x51 | SCANCODE_EXTENDED;
        case INSERT -> 0x52 | SCANCODE_EXTENDED; case DELETE -> 0x53 | SCANCODE_EXTENDED;
        case ALT_GRAPH -> 0x38 | SCANCODE_EXTENDED;
        case META, WINDOWS -> 0x5b | SCANCODE_EXTENDED;
        case CONTEXT_MENU -> 0x5d | SCANCODE_EXTENDED;
        default -> -1;
        };
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static boolean isKeypadLockDependent(KeyCode code) {
        return switch (code) {
        case NUMPAD0, NUMPAD1, NUMPAD2, NUMPAD3, NUMPAD4,
             NUMPAD5, NUMPAD6, NUMPAD7, NUMPAD8, NUMPAD9,
             KP_UP, KP_DOWN, KP_LEFT, KP_RIGHT -> true;
        default -> false;
        };
    }

    public static int toggleFlags(boolean capsLock, boolean numLock, boolean scrollLock) {
        return (scrollLock ? TS_SYNC_SCROLL_LOCK : 0)
                | (numLock ? TS_SYNC_NUM_LOCK : 0)
                | (capsLock ? TS_SYNC_CAPS_LOCK : 0);
    }

    private void refreshLocalLockKeyState() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            capsLock = (WindowsKeyboard.INSTANCE.GetKeyState(VK_CAPITAL) & 1) != 0;
            numLock = (WindowsKeyboard.INSTANCE.GetKeyState(VK_NUMLOCK) & 1) != 0;
            scrollLock = (WindowsKeyboard.INSTANCE.GetKeyState(VK_SCROLL) & 1) != 0;
        } catch (RuntimeException | UnsatisfiedLinkError ignored) {
            // Preserve the last known state on unsupported/restricted systems.
        }
    }

    private interface WindowsKeyboard extends Library {
        WindowsKeyboard INSTANCE = Native.load("user32", WindowsKeyboard.class);

        short GetKeyState(int virtualKey);
    }
}
