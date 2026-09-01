package com.tangluobo.rdp4j.frontend;

import com.tangluobo.rdp4j.IContext;
import com.tangluobo.rdp4j.State;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;

/** Creates one toolkit-specific canvas/input pair for each connection attempt. */
public interface RdpFrontend {

    RdesktopCanvas createCanvas(IContext context, State state);

    default void executeOnUiThread(Runnable action) {
        action.run();
    }

    /**
     * Receives focus notifications from toolkit-native desktop controls.
     * Clipboard redirection uses this to re-announce local clipboard formats
     * whenever the user returns to the RDP view.
     */
    default void setFocusGainedListener(Runnable listener) {
    }

    default void disposeCanvas(RdesktopCanvas canvas) {
        if (canvas != null && canvas.getInput() != null) {
            canvas.getInput().dispose();
        }
    }
}
