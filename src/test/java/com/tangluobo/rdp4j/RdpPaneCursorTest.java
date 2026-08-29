package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import javafx.scene.image.WritableImage;

class RdpPaneCursorTest {

    @Test
    void cursorImageKeepsPixelsAndPremultipliedAlphaWithoutInterpolation() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xffff0000);
        source.setRGB(1, 0, 0x8000ff00);
        source.setRGB(0, 1, 0x00000000);
        source.setRGB(1, 1, 0xff0000ff);

        WritableImage image = RdpPane.createFxCursorImage(source, 2, 2);

        assertEquals(2, (int) image.getWidth());
        assertEquals(2, (int) image.getHeight());
        assertEquals(0xffff0000, image.getPixelReader().getArgb(0, 0));
        assertEquals(0x8000ff00, image.getPixelReader().getArgb(1, 0));
        assertEquals(0x00000000, image.getPixelReader().getArgb(0, 1));
        assertEquals(0xff0000ff, image.getPixelReader().getArgb(1, 1));
    }

    @Test
    void nonIntegerDpiScalingAntialiasesDiagonalsWithoutDarkTransparentFringes() {
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x <= y; x++) {
                source.setRGB(x, y, 0xffffffff);
            }
        }

        WritableImage image = RdpPane.createFxCursorImage(source, 6, 6);

        int partialPixels = 0;
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                int argb = image.getPixelReader().getArgb(x, y);
                int alpha = argb >>> 24;
                if (alpha > 0 && alpha < 255) {
                    partialPixels++;
                    assertTrue(((argb >>> 16) & 0xff) >= 245,
                            "alpha resampling must not create a dark fringe");
                    assertTrue(((argb >>> 8) & 0xff) >= 245);
                    assertTrue((argb & 0xff) >= 245);
                }
            }
        }
        assertTrue(partialPixels > 0,
                "a 150% diagonal scale should contain antialiased coverage pixels");
    }
}
