package com.tangluobo.rdp4j.frontend;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;

import com.tangluobo.rdp4j.IContext;
import com.tangluobo.rdp4j.State;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;

import javafx.application.Platform;
import javafx.scene.Node;

/** Pure JavaFX frontend: no SwingNode, JComponent, EDT, or AWT input events. */
public final class FxRdpFrontend implements RdpFrontend {

    private volatile FxRdpDisplay display;
    private volatile BiConsumer<Integer, Integer> pointerMovedListener = (x, y) -> { };
    private volatile BiConsumer<Integer, Integer> serverPointerMovedListener = (x, y) -> { };

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
        canvas.setInput(new FxRdpInput(state, nextDisplay, this::notifyPointerMoved));
        display = nextDisplay;
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

    public Node getView() {
        FxRdpDisplay current = display;
        return current == null ? null : current.getView();
    }

    public FxRdpDisplay getDisplay() {
        return display;
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
        executeOnUiThread(() -> RdpFrontend.super.disposeCanvas(canvas));
    }
}
