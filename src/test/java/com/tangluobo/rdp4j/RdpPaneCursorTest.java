package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

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
    void recognizesCursorSizedChangesAtBothOldAndNewPointerPositions() {
        assertTrue(FxRdpDisplay.resemblesSoftwareCursorMovement(80, 2401, 95, 2401));
        assertFalse(FxRdpDisplay.resemblesSoftwareCursorMovement(0, 2401, 95, 2401));
        assertFalse(FxRdpDisplay.resemblesSoftwareCursorMovement(80, 2401, 1800, 2401),
                "large video or control repaints must not be treated as a software cursor");
    }

    @Test
    void limitsFrameCursorInferenceToLegacyVmCursorSize() {
        assertTrue(FxRdpDisplay.isLegacyVmSoftwareCursorCandidate(24, 24));
        assertFalse(FxRdpDisplay.isLegacyVmSoftwareCursorCandidate(32, 32),
                "Windows protocol cursors must remain visible during context-menu repaints");
    }

}
