package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.graphics.RdesktopCanvas;
import com.tangluobo.rdp4j.graphics.WrappedImage;

class InputTest {

    @Test
    void rdpCanvasDisablesLocalInputMethodHandling() {
        Options options = new Options();
        options.setWidth(8);
        options.setHeight(8);
        RecordingDisplay display = new RecordingDisplay();

        new RdesktopCanvas(new NoOpContext(), new State(options), display);

        assertTrue(display.inputMethodConfigurationCalls > 0);
        assertFalse(display.inputMethodsEnabled);
        assertEquals(0, display.getInputMethodListeners().length,
                "local IME committed text must not be forwarded by the RDP canvas");
    }

    private static final class RecordingDisplay extends WrappedImage {
        private int inputMethodConfigurationCalls;
        private boolean inputMethodsEnabled = true;

        private RecordingDisplay() {
            super(8, 8, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public void enableInputMethods(boolean enable) {
            super.enableInputMethods(enable);
            inputMethodConfigurationCalls++;
            inputMethodsEnabled = enable;
        }
    }

    private static final class NoOpContext implements IContext {
        @Override public void dispose() { }
        @Override public void error(Exception e, boolean dispose) { }
        @Override public byte[] loadLicense() throws IOException { return null; }
        @Override public void saveLicense(byte[] license) throws IOException { }
        @Override public void screenResized(int width, int height, boolean clientInitiated) { }
        @Override public void setLoggedOn() { }
        @Override public void toggleFullScreen() { }
        @Override public void ready(ReadyType ready) { }
    }
}
