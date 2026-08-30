package com.tangluobo.rdp4j.frontend;

import javax.swing.SwingUtilities;

import com.tangluobo.rdp4j.IContext;
import com.tangluobo.rdp4j.State;
import com.tangluobo.rdp4j.graphics.RdesktopCanvas;

/** Original AWT/Swing implementation retained for standalone Swing clients. */
public final class SwingRdpFrontend implements RdpFrontend {

    @Override
    public RdesktopCanvas createCanvas(IContext context, State state) {
        return new RdesktopCanvas(context, state);
    }

    @Override
    public void executeOnUiThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
