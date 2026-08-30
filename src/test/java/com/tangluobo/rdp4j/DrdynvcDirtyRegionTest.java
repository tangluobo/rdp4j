package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DrdynvcDirtyRegionTest {

    @Test
    void unionsAndClipsSurfaceUpdatesBeforeRendering() {
        DrdynvcChannel.GfxSurface surface = new DrdynvcChannel.GfxSurface(100, 80);

        surface.markDirty(20, 30, 10, 10);
        surface.markDirty(25, 5, 100, 30);
        DrdynvcChannel.DirtyRectangle dirty = surface.consumeDirtyRegion();

        assertEquals(20, dirty.left);
        assertEquals(5, dirty.top);
        assertEquals(100, dirty.right);
        assertEquals(40, dirty.bottom);
        assertFalse(surface.hasDirtyRegion());
        assertNull(surface.consumeDirtyRegion());
    }
}
