package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

class RdpPaneKeyboardBridgeTest {

    @Test
    void convertsJavaFxModifiersForDirectRdpDispatch() {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A,
                true, true, true, true);

        int expected = java.awt.event.InputEvent.SHIFT_DOWN_MASK
                | java.awt.event.InputEvent.CTRL_DOWN_MASK
                | java.awt.event.InputEvent.ALT_DOWN_MASK
                | java.awt.event.InputEvent.META_DOWN_MASK;
        assertEquals(expected, RdpPane.toAwtModifiers(event));
    }

    @Test
    void preservesNumpadLocationForRemoteKeymap() {
        assertEquals(java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD,
                RdpPane.toAwtKeyLocation(KeyCode.NUMPAD1));
        assertEquals(java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD,
                RdpPane.toAwtKeyLocation(KeyCode.KP_DOWN));
        assertEquals(java.awt.event.KeyEvent.KEY_LOCATION_STANDARD,
                RdpPane.toAwtKeyLocation(KeyCode.DIGIT1));
    }
}
