package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.Point;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.frontend.FxRdpDisplay;

import javafx.scene.image.WritableImage;

class RdpPaneCursorTest {

    @Test
    void cursorImageKeepsPixelsAndPremultipliedAlphaWithoutInterpolation() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xffff0000);
        source.setRGB(1, 0, 0x8000ff00);
        source.setRGB(0, 1, 0x00000000);
        source.setRGB(1, 1, 0xff0000ff);

        WritableImage image = FxRdpDisplay.createFxImage(source);

        assertEquals(2, (int) image.getWidth());
        assertEquals(2, (int) image.getHeight());
        assertEquals(0xffff0000, image.getPixelReader().getArgb(0, 0));
        assertEquals(0x8000ff00, image.getPixelReader().getArgb(1, 0));
        assertEquals(0x00000000, image.getPixelReader().getArgb(0, 1));
        assertEquals(0xff0000ff, image.getPixelReader().getArgb(1, 1));
    }

    @Test
    void unsupportedCursorSizeUsesTransparentPaddingWithoutScalingTheShape() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xffff0000);
        source.setRGB(1, 0, 0x0000ff00); // transparent pixel with residual RGB
        source.setRGB(0, 1, 0xff00ff00);
        source.setRGB(1, 1, 0xff0000ff);

        WritableImage image = FxRdpDisplay.createFxCursorImage(source, 4, 4);

        assertEquals(4, (int) image.getWidth());
        assertEquals(4, (int) image.getHeight());
        assertEquals(0xffff0000, image.getPixelReader().getArgb(0, 0));
        assertEquals(0, image.getPixelReader().getArgb(1, 0),
                "transparent source RGB must be cleared");
        assertEquals(0xff00ff00, image.getPixelReader().getArgb(0, 1));
        assertEquals(0xff0000ff, image.getPixelReader().getArgb(1, 1));
        assertEquals(0, image.getPixelReader().getArgb(3, 3),
                "native-size padding must stay fully transparent");
    }

    @Test
    void hiddenOnePixelCursorProducesOnlyTransparentNativePixels() {
        BufferedImage hidden = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        WritableImage image = FxRdpDisplay.createFxCursorImage(hidden, 4, 4);

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0, image.getPixelReader().getArgb(x, y));
            }
        }
    }

    @Test
    void recognizesTransparentCustomVmCursorAsHiddenState() {
        BufferedImage hidden = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        hidden.setRGB(4, 4, 0x0000ff00); // residual RGB with zero alpha
        BufferedImage visible = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        visible.setRGB(4, 4, 0x01000000);

        assertTrue(FxRdpDisplay.isFullyTransparentCursor(hidden));
        assertFalse(FxRdpDisplay.isFullyTransparentCursor(visible));
    }

    @Test
    void recognizesAClientCursorAlreadyDrawnIntoRemoteFrame() {
        BufferedImage cursor = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        cursor.setRGB(0, 0, 0xff000000);
        cursor.setRGB(1, 0, 0xffffffff);
        cursor.setRGB(1, 1, 0xff000000);
        cursor.setRGB(2, 1, 0xffffffff);
        cursor.setRGB(2, 2, 0xff000000);
        cursor.setRGB(3, 2, 0xffffffff);
        cursor.setRGB(3, 3, 0xff000000);
        cursor.setRGB(0, 3, 0xffffffff);

        BufferedImage frame = new BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB);
        int pointerX = 30;
        int pointerY = 20;
        Point hotspot = new Point(1, 1);
        int drawX = pointerX - hotspot.x + 3;
        int drawY = pointerY - hotspot.y - 2;
        frame.getGraphics().drawImage(cursor, drawX, drawY, null);

        assertEquals(1.0, FxRdpDisplay.cursorMatchScore(
                cursor, hotspot, frame, pointerX, pointerY, 4));
        assertTrue(FxRdpDisplay.cursorMatchScore(
                cursor, hotspot, frame, 60, 40, 4) < 0.7);
    }

}
