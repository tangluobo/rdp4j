package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RdpPaneFullScreenEdgeTest {

    @Test
    void recognizesEntireFivePixelTopEdge() {
        assertTrue(RdpPane.isAtFullScreenTopEdge(0, 0, 0, 0, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(960, 5, 0, 0, 1920));
        assertTrue(RdpPane.isAtFullScreenTopEdge(-1, -1197, -1920, -1200, 1920));
    }

    @Test
    void rejectsPointsOutsideDesktopOrBelowTriggerArea() {
        assertFalse(RdpPane.isAtFullScreenTopEdge(-1, 2, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(1920, 2, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, -1, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(20, 6, 0, 0, 1920));
        assertFalse(RdpPane.isAtFullScreenTopEdge(0, 0, 0, 0, 0));
    }

    @Test
    void offsetsControlBarWhenApplicationRunsInsideAnotherRdpSession() {
        assertEquals(0, RdpPane.controlBarOffsetForSession("Console", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession("RDP-Tcp#7", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession("rdp-tcp#2", null));
        assertEquals(44, RdpPane.controlBarOffsetForSession(null, "xrdp-session"));
    }
}
