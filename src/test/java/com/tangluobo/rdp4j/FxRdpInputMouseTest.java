package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.frontend.FxRdpInput;
import com.tangluobo.rdp4j.frontend.FxRdpDisplay;

class FxRdpInputMouseTest {

    @Test
    void mapsRenderedImageCoordinatesToRemoteDesktop() {
        assertEquals(0, FxRdpInput.mapRemoteCoordinate(100, 100, 800, 1600));
        assertEquals(800, FxRdpInput.mapRemoteCoordinate(500, 100, 800, 1600));
        assertEquals(1599, FxRdpInput.mapRemoteCoordinate(899.9, 100, 800, 1600));
    }

    @Test
    void clampsLetterboxMovementToRemoteEdgesInsteadOfDroppingIt() {
        assertEquals(0, FxRdpInput.mapRemoteCoordinate(20, 100, 800, 1600));
        assertEquals(1599, FxRdpInput.mapRemoteCoordinate(950, 100, 800, 1600));
    }

    @Test
    void ignoresSmallServerEchoesButKeepsRealVmRecentering() {
        assertTrue(FxRdpDisplay.isLocalPointerEcho(1001, 501, 1000, 500));
        assertFalse(FxRdpDisplay.isLocalPointerEcho(960, 540, 1919, 500));

        assertFalse(FxRdpDisplay.isMeaningfulScreenWarp(100, 100, 103, 101),
                "rounding differences must not pull slow movement back to a border");
        assertTrue(FxRdpDisplay.isMeaningfulScreenWarp(1900, 500, 960, 540),
                "a nested VM recenter jump must still move the local pointer");
    }

    @Test
    void serverPositionRehidesOuterCursorBeforePhysicalWarpFiltering() {
        assertFalse(FxRdpDisplay.shouldHideForServerPointerPosition(false, "custom-24x24"));
        assertFalse(FxRdpDisplay.shouldHideForServerPointerPosition(true, "hidden-system"));
        assertFalse(FxRdpDisplay.shouldHideForServerPointerPosition(true,
                "hidden-recapture-position"));
        assertTrue(FxRdpDisplay.shouldHideForServerPointerPosition(true, "custom-24x24"));
        assertTrue(FxRdpDisplay.shouldHideForServerPointerPosition(true, "default"));
    }
}
