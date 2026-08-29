package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.layers.Rdp;

class OptionsTest {

    @Test
    void fullWindowDraggingIsEnabledByDefault() {
        Options options = new Options();

        assertEquals(0, options.getRdp5PerformanceFlags()
                & Rdp.PERF_DISABLE_FULLWINDOW_DRAG,
                "the server must repaint window contents while moving and resizing");
    }
}
