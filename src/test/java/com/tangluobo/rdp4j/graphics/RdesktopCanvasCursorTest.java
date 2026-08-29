package com.tangluobo.rdp4j.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.IContext;
import com.tangluobo.rdp4j.Options;
import com.tangluobo.rdp4j.State;

class RdesktopCanvasCursorTest {

    @Test
    void decodesAsymmetricMonochromePointerWithoutHorizontalMirroring() {
        RdesktopCanvas canvas = createCanvas();

        // The 1-bpp New Pointer encoding is top-down. The top row has one
        // opaque pixel at the left and the bottom row one at the right. This
        // catches accidental flipping or mirroring of the default arrow shape.
        byte[] xorMask = { 0, 0, 0, 0 };
        byte[] andMask = { 0x7f, 0, (byte) 0xfe, 0 };

        RdpCursor cursor = canvas.createCursor(0, 0, 8, 2, andMask, xorMask, 0, 1);
        java.awt.image.BufferedImage image = (java.awt.image.BufferedImage) cursor.getData();

        assertEquals(0xff, image.getRGB(0, 0) >>> 24);
        assertEquals(0x00, image.getRGB(7, 0) >>> 24);
        assertEquals(0x00, image.getRGB(0, 1) >>> 24);
        assertEquals(0xff, image.getRGB(7, 1) >>> 24);
        assertEquals(0, cursor.getHotspot().x);
        assertEquals(0, cursor.getHotspot().y);
    }

    @Test
    void preservesBgraAlphaForAntialiased32BitPointers() {
        RdesktopCanvas canvas = createCanvas();

        byte[] xorMask = {
                0, 0, 0, 0,
                (byte) 255, (byte) 255, (byte) 255, (byte) 128,
                0, 0, 0, (byte) 255
        };
        byte[] andMask = { 0, 0 };

        RdpCursor cursor = canvas.createCursor(0, 0, 3, 1, andMask, xorMask, 0, 32);
        java.awt.image.BufferedImage image = (java.awt.image.BufferedImage) cursor.getData();

        assertEquals(0x00, image.getRGB(0, 0) >>> 24, "transparent background must stay transparent");
        assertEquals(0x80, image.getRGB(1, 0) >>> 24, "antialiased edge alpha must be preserved");
        assertEquals(0xff, image.getRGB(2, 0) >>> 24, "opaque pointer pixels must stay opaque");
    }

    @Test
    void decodesBottomUpRowsAndKeepsHotspotCoordinates() {
        RdesktopCanvas canvas = createCanvas();

        // Three 24-bpp pixels occupy 9 bytes and are padded to 10 bytes per
        // scan line. The wire format carries BGR pixels and the bottom row first.
        byte[] xorMask = {
                (byte) 255, 0, 0, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255, 0,
                0, 0, (byte) 255, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255, 0
        };
        byte[] andMask = { 0, 0, 0, 0 };

        RdpCursor cursor = canvas.createCursor(1, 0, 3, 2, andMask, xorMask, 0, 24);
        java.awt.image.BufferedImage image = (java.awt.image.BufferedImage) cursor.getData();

        assertEquals(0xffff0000, image.getRGB(0, 0), "top row must come from the second wire scan line");
        assertEquals(0xff00ff00, image.getRGB(1, 0));
        assertEquals(0xffffff00, image.getRGB(2, 0));
        assertEquals(0xff0000ff, image.getRGB(0, 1), "bottom row must come from the first wire scan line");
        assertEquals(0xff00ffff, image.getRGB(1, 1));
        assertEquals(0xffff00ff, image.getRGB(2, 1));
        assertEquals(1, cursor.getHotspot().x, "hotspot is already expressed in top-down image coordinates");
        assertEquals(0, cursor.getHotspot().y);
    }

    @Test
    void firstRemoteUpdateListenerFiresOnceOnFirstNonEmptyRepaint() {
        WrappedImage image = new WrappedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        AtomicInteger callbacks = new AtomicInteger();
        image.setFirstRemoteUpdateListener(callbacks::incrementAndGet);

        image.repaintRemote(0, 0, 0, 0);
        assertEquals(0, callbacks.get());
        image.repaintRemote(0, 0, 2, 2);
        image.repaintRemote(0, 0, 2, 2);

        assertEquals(1, callbacks.get());
    }

    private static RdesktopCanvas createCanvas() {
        Options options = new Options();
        options.setWidth(8);
        options.setHeight(8);
        State state = new State(options);
        return new RdesktopCanvas(new NoOpContext(), state);
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
