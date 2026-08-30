package com.tangluobo.rdp4j.frontend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class FxRdpDisplayPixelCopyTest {

    @Test
    void copiesOnlyRequestedRowsUsingSourceAndTargetStrides() {
        int[] target = new int[5 * 4];
        int[] source = {
                0, 0x00112233, 0x80445566, 0,
                0, 0x00778899, 0xffaabbcc, 0
        };

        FxRdpDisplay.copyOpaquePixels(target, 5, 2, 1,
                source, 1, 4, 2, 2);

        int[] expected = new int[5 * 4];
        expected[7] = 0xff112233;
        expected[8] = 0xff445566;
        expected[12] = 0xff778899;
        expected[13] = 0xffaabbcc;
        assertArrayEquals(expected, target);
    }
}
