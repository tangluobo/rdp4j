package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RdpPaneFullScreenEdgeTest {

    @Test
    void recognizesEntireFivePixelTopEdge() {
        assertTrue(RdpPane.isAtFullScreenTopEdge(0, 0, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(960, 5, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(1919, 3, 1920));
    }

    @Test
    void rejectsPointsOutsideDesktopOrBelowTriggerArea() {
        assertFalse(RdpPane.isAtFullScreenTopEdge(-1, 2, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(1920, 2, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, -1, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, 6, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(0, 0, 0));
    }
}
