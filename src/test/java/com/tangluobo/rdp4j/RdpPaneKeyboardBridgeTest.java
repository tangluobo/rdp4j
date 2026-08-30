package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javafx.scene.input.KeyCode;
import com.tangluobo.rdp4j.frontend.FxRdpInput;

class RdpPaneKeyboardBridgeTest {

    @Test
    void mapsOrdinaryKeysDirectlyToSetOneScancodes() {
        assertEquals(0x1e, FxRdpInput.scancodeFor(KeyCode.A));
        assertEquals(0x02, FxRdpInput.scancodeFor(KeyCode.DIGIT1));
        assertEquals(0x53 | 0x80, FxRdpInput.scancodeFor(KeyCode.DELETE));
    }

    @Test
    void mapsNumpadKeysWithoutAwtLocationTranslation() {
        assertEquals(0x4f, FxRdpInput.scancodeFor(KeyCode.NUMPAD1));
        assertEquals(0x50, FxRdpInput.scancodeFor(KeyCode.KP_DOWN));
        assertEquals(0x48, FxRdpInput.scancodeFor(KeyCode.KP_UP));
        assertEquals(0x35 | 0x80, FxRdpInput.scancodeFor(KeyCode.DIVIDE));
    }

    @Test
    void identifiesKeysThatNeedLateNumLockSynchronization() {
        assertTrue(FxRdpInput.isKeypadLockDependent(KeyCode.NUMPAD0));
        assertTrue(FxRdpInput.isKeypadLockDependent(KeyCode.NUMPAD9));
        assertTrue(FxRdpInput.isKeypadLockDependent(KeyCode.KP_UP));
        assertFalse(FxRdpInput.isKeypadLockDependent(KeyCode.DIGIT1));
        assertFalse(FxRdpInput.isKeypadLockDependent(KeyCode.ADD));
    }

    @Test
    void buildsAbsolutePureFxToggleFlags() {
        assertEquals(0x02, FxRdpInput.toggleFlags(false, true, false));
        assertEquals(0x07, FxRdpInput.toggleFlags(true, true, true));
    }
}
