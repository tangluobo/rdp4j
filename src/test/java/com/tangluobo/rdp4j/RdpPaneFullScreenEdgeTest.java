package com.tangluobo.rdp4j;

import java.util.List;

import javafx.geometry.Rectangle2D;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RdpPaneFullScreenEdgeTest {

    private static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 1920, 1080);
    private static final Rectangle2D SECONDARY = new Rectangle2D(1920, 0, 1920, 1080);

    @Test
    void recognizesEntireTopEdgeTriggerArea() {
        assertTrue(RdpPane.isAtFullScreenTopEdge(0, 0, 0, 0, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(960, 12, 0, 0, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(-1, -1197, -1920, -1200, 1920));
    }

    @Test
    void rejectsPointsOutsideDesktopOrBelowTriggerArea() {
        assertFalse(RdpPane.isAtFullScreenTopEdge(-1, 2, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(1920, 2, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, -1, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, 13, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(0, 0, 0, 0, 0));
        assertFalse(RdpPane.isAtFullScreenTopEdge(Double.NaN, 0, 0, 0, 1920));
    }

    @Test
    void recognizesSceneTopEdgeIndependentlyOfWindowCoordinates() {
        assertTrue(RdpPane.isAtSceneTopEdge(0));
        assertTrue(RdpPane.isAtSceneTopEdge(12));
        assertFalse(RdpPane.isAtSceneTopEdge(-0.1));
        assertFalse(RdpPane.isAtSceneTopEdge(12.1));
        assertFalse(RdpPane.isAtSceneTopEdge(Double.NaN));
    }

    @Test
    void mapsLocalTriggerHeightToRemoteDesktopCoordinates() {
        assertTrue(RdpPane.isAtRemoteTopEdge(12, 1080, 1080));
        assertTrue(RdpPane.isAtRemoteTopEdge(24, 2160, 1080));
        assertFalse(RdpPane.isAtRemoteTopEdge(25, 2160, 1080));
        assertFalse(RdpPane.isAtRemoteTopEdge(-1, 1080, 1080));
        assertFalse(RdpPane.isAtRemoteTopEdge(0, 0, 1080));
        assertFalse(RdpPane.isAtRemoteTopEdge(0, 1080, 0));
    }

    @Test
    void hiddenControlBarLeavesThreePixelHoverHandle() {
        assertEquals(-37, RdpPane.hiddenControlBarTranslateY(40));
        assertEquals(0, RdpPane.hiddenControlBarTranslateY(3));
        assertEquals(0, RdpPane.hiddenControlBarTranslateY(0));
        assertEquals(0, RdpPane.hiddenControlBarTranslateY(Double.NaN));
    }

    @Test
    void clampsHorizontalControlBarDragInsideFullScreenWidth() {
        assertEquals(300, RdpPane.clampControlBarTranslateX(500, 1020, 420));
        assertEquals(-300, RdpPane.clampControlBarTranslateX(-500, 1020, 420));
        assertEquals(125, RdpPane.clampControlBarTranslateX(125, 1020, 420));
    }

    @Test
    void centersControlBarWhenHorizontalDragBoundsAreInvalid() {
        assertEquals(0, RdpPane.clampControlBarTranslateX(100, 300, 420));
        assertEquals(0, RdpPane.clampControlBarTranslateX(Double.NaN, 1020, 420));
        assertEquals(0, RdpPane.clampControlBarTranslateX(100, 0, 420));
    }

    @Test
    void defersAutomaticFullScreenExitCausedByFocusLoss() {
        assertTrue(RdpPane.shouldDeferFullScreenExit(false, false));
        assertTrue(RdpPane.shouldDeferFullScreenExit(true, true));
    }

    @Test
    void treatsFocusedFullScreenExitAsExplicitUserAction() {
        assertFalse(RdpPane.shouldDeferFullScreenExit(true, false));
    }

    @Test
    void restoresFullScreenAfterItsWindowIsDeiconified() {
        assertTrue(RdpPane.shouldRestoreAfterDeiconify(true, false, false));
        assertFalse(RdpPane.shouldRestoreAfterDeiconify(false, false, false));
        assertFalse(RdpPane.shouldRestoreAfterDeiconify(true, true, false));
        assertFalse(RdpPane.shouldRestoreAfterDeiconify(true, false, true));
    }

    @Test
    void groupsEdgeDiagnosticsWithoutLoggingEveryMousePixel() {
        assertEquals(0, RdpPane.edgeDiagnosticBand(0));
        assertEquals(0, RdpPane.edgeDiagnosticBand(12));
        assertEquals(1, RdpPane.edgeDiagnosticBand(13));
        assertEquals(2, RdpPane.edgeDiagnosticBand(50));
        assertEquals(3, RdpPane.edgeDiagnosticBand(100));
        assertEquals(4, RdpPane.edgeDiagnosticBand(200));
        assertEquals(Integer.MAX_VALUE, RdpPane.edgeDiagnosticBand(201));
        assertEquals(Integer.MAX_VALUE, RdpPane.edgeDiagnosticBand(Double.NaN));
    }

    @Test
    void offsetsControlBarWhenApplicationRunsInsideAnotherRdpSession() {
        assertEquals(0, RdpPane.controlBarOffsetForSession("Console", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession("RDP-Tcp#7", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession("rdp-tcp#2", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession(null, "xrdp-session"));
    }

    @Test
    void selectsMonitorWithLargestWindowOverlap() {
        Rectangle2D mostlySecondary = new Rectangle2D(1700, 100, 1000, 800);

        assertEquals(SECONDARY, RdpPane.selectScreenBounds(mostlySecondary,
                List.of(PRIMARY, SECONDARY), PRIMARY));
    }

    @Test
    void selectsSecondaryMonitorForWindowExactlyOnItsOrigin() {
        Rectangle2D secondaryWindow = new Rectangle2D(1920, 0, 1280, 720);

        assertEquals(SECONDARY, RdpPane.selectScreenBounds(secondaryWindow,
                List.of(PRIMARY, SECONDARY), PRIMARY));
    }

    @Test
    void fallsBackWhenWindowDoesNotIntersectAnyMonitor() {
        Rectangle2D offScreen = new Rectangle2D(-10000, -10000, 800, 600);

        assertEquals(PRIMARY, RdpPane.selectScreenBounds(offScreen,
                List.of(PRIMARY, SECONDARY), PRIMARY));
    }
}
