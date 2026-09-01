package com.tangluobo.rdp4j.frontend;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;

import com.tangluobo.rdp4j.IContext;
import com.tangluobo.rdp4j.State;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;

/** Pure JavaFX frontend: no SwingNode, JComponent, EDT, or AWT input events. */
public final class FxRdpFrontend implements RdpFrontend {

    private volatile FxRdpDisplay display;
    private volatile FxRdpInput input;
    private volatile BiConsumer<Integer, Integer> pointerMovedListener = (x, y) -> { };
    private volatile BiConsumer<Integer, Integer> serverPointerMovedListener = (x, y) -> { };
    private volatile Runnable focusGainedListener = () -> { };

    @Override
    public RdesktopCanvas createCanvas(IContext context, State state) {
        if (Platform.isFxApplicationThread()) {
            return createOnFxThread(context, state);
        }
        FutureTask<RdesktopCanvas> task = new FutureTask<>(() -> createOnFxThread(context, state));
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the JavaFX RDP frontend", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to create the JavaFX RDP frontend", e.getCause());
        }
    }

    private RdesktopCanvas createOnFxThread(IContext context, State state) {
        FxRdpDisplay nextDisplay = new FxRdpDisplay(state.getWidth(), state.getHeight(),
                this::notifyServerPointerMoved);
        RdesktopCanvas canvas = new RdesktopCanvas(context, state, nextDisplay, false);
        FxRdpInput nextInput = new FxRdpInput(state, nextDisplay,
                this::notifyPointerMoved, this::notifyFocusGained);
        canvas.setInput(nextInput);
        display = nextDisplay;
        input = nextInput;
        return canvas;
    }

    public void setPointerMovedListener(BiConsumer<Integer, Integer> listener) {
        pointerMovedListener = listener == null ? (x, y) -> { } : listener;
    }

    private void notifyPointerMoved(int x, int y) {
        pointerMovedListener.accept(x, y);
    }

    public void setServerPointerMovedListener(BiConsumer<Integer, Integer> listener) {
        serverPointerMovedListener = listener == null ? (x, y) -> { } : listener;
    }

    private void notifyServerPointerMoved(int x, int y) {
        serverPointerMovedListener.accept(x, y);
    }

    @Override
    public void setFocusGainedListener(Runnable listener) {
        focusGainedListener = listener == null ? () -> { } : listener;
    }

    void notifyFocusGained() {
        focusGainedListener.run();
    }

    public Node getView() {
        FxRdpDisplay current = display;
        return current == null ? null : current.getView();
    }

    public FxRdpDisplay getDisplay() {
        return display;
    }

    /** Sends a full-screen Scene key event directly to the active RDP input. */
    public boolean forwardKeyEvent(KeyEvent event) {
        FxRdpInput current = input;
        if (current == null) {
            return false;
        }
        current.forwardKeyEvent(event);
        return true;
    }

    public boolean hasKeyboardInput() {
        return input != null;
    }

    /** Queues a Windows hook scan code onto the JavaFX/RDP input thread. */
    public boolean forwardNativeKey(int scanCode, boolean extended, boolean release) {
        if (input == null) {
            return false;
        }
        executeOnUiThread(() -> {
            FxRdpInput current = input;
            if (current != null) {
                current.forwardNativeKey(scanCode, extended, release);
            }
        });
        return true;
    }

    public void setScaleToFit(boolean scaleToFit) {
        FxRdpDisplay current = display;
        if (current != null) {
            executeOnUiThread(() -> current.setScaleToFit(scaleToFit));
        }
    }

    @Override
    public void executeOnUiThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    @Override
    public void disposeCanvas(RdesktopCanvas canvas) {
        executeOnUiThread(() -> {
            FxRdpInput disposedInput = canvas != null && canvas.getInput() instanceof FxRdpInput fxInput
                    ? fxInput : null;
            RdpFrontend.super.disposeCanvas(canvas);
            if (input == disposedInput) {
                input = null;
            }
        });
    }
}
