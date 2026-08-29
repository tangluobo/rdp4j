package com.tangluobo.rdp4j.keymapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.io.InputStream;
import java.net.URL;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.Options;

class KeyCodeFileBasedTest {

    @Test
    void printableKeyUsesPressAndReleaseWhenImeSuppressesTypedEvent() throws Exception {
        KeyCode_FileBased keymap = loadUsKeymap();
        Canvas component = new Canvas();

        String press = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED));
        String release = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_RELEASED,
                KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED));

        assertStroke(press, 0x1e, KeyCode_FileBased.DOWN);
        assertStroke(release, 0x1e, KeyCode_FileBased.UP);
    }

    @Test
    void typedEventIsNotSentTwiceAfterPhysicalPressMatched() throws Exception {
        KeyCode_FileBased keymap = loadUsKeymap();
        Canvas component = new Canvas();

        String press = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_A, 'a'));
        String typed = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_TYPED,
                KeyEvent.VK_UNDEFINED, 'a'));
        String release = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_RELEASED,
                KeyEvent.VK_A, 'a'));

        assertStroke(press, 0x1e, KeyCode_FileBased.DOWN);
        assertEquals("", typed);
        assertStroke(release, 0x1e, KeyCode_FileBased.UP);
    }

    @Test
    void numberSelectionKeyDoesNotDependOnTypedEvent() throws Exception {
        KeyCode_FileBased keymap = loadUsKeymap();
        Canvas component = new Canvas();

        String press = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_1, KeyEvent.CHAR_UNDEFINED));
        String release = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_RELEASED,
                KeyEvent.VK_1, KeyEvent.CHAR_UNDEFINED));

        assertStroke(press, 0x02, KeyCode_FileBased.DOWN);
        assertStroke(release, 0x02, KeyCode_FileBased.UP);
    }

    @Test
    void numericKeypadDigitsUseTheirPhysicalScanCodes() throws Exception {
        KeyCode_FileBased keymap = loadUsKeymap();
        Canvas component = new Canvas();
        int[] keyCodes = {
                KeyEvent.VK_NUMPAD0, KeyEvent.VK_NUMPAD1, KeyEvent.VK_NUMPAD2,
                KeyEvent.VK_NUMPAD3, KeyEvent.VK_NUMPAD4, KeyEvent.VK_NUMPAD5,
                KeyEvent.VK_NUMPAD6, KeyEvent.VK_NUMPAD7, KeyEvent.VK_NUMPAD8,
                KeyEvent.VK_NUMPAD9
        };
        int[] scanCodes = { 0x52, 0x4f, 0x50, 0x51, 0x4b, 0x4c, 0x4d, 0x47, 0x48, 0x49 };

        for (int i = 0; i < keyCodes.length; i++) {
            String press = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                    keyCodes[i], KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD));
            String release = keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_RELEASED,
                    keyCodes[i], KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD));
            assertStroke(press, scanCodes[i], KeyCode_FileBased.DOWN);
            assertStroke(release, scanCodes[i], KeyCode_FileBased.UP);
        }
    }

    @Test
    void numericKeypadOperatorsIncludeExtendedDivideScanCode() throws Exception {
        KeyCode_FileBased keymap = loadUsKeymap();
        Canvas component = new Canvas();

        assertStroke(keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_ADD, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD)),
                0x4e, KeyCode_FileBased.DOWN);
        assertStroke(keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_SUBTRACT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD)),
                0x4a, KeyCode_FileBased.DOWN);
        assertStroke(keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_MULTIPLY, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD)),
                0x37, KeyCode_FileBased.DOWN);
        assertStroke(keymap.getKeyStrokes(keyEvent(component, KeyEvent.KEY_PRESSED,
                KeyEvent.VK_DIVIDE, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD)),
                0x35 | KeyCode_FileBased.SCANCODE_EXTENDED, KeyCode_FileBased.DOWN);
    }

    private static KeyCode_FileBased loadUsKeymap() throws Exception {
        Options options = new Options();
        URL url = KeyCodeFileBasedTest.class.getResource("/keymaps/en-us");
        try (InputStream input = url.openStream()) {
            return new KeyCode_FileBased(options, url, input);
        }
    }

    private static KeyEvent keyEvent(Canvas component, int id, int keyCode, char keyChar) {
        int location = id == KeyEvent.KEY_TYPED
                ? KeyEvent.KEY_LOCATION_UNKNOWN : KeyEvent.KEY_LOCATION_STANDARD;
        return keyEvent(component, id, keyCode, keyChar, location);
    }

    private static KeyEvent keyEvent(Canvas component, int id, int keyCode, char keyChar, int location) {
        return new KeyEvent(component, id, System.currentTimeMillis(), 0,
                keyCode, keyChar, location);
    }

    private static void assertStroke(String strokes, int scancode, int action) {
        assertEquals(2, strokes.length());
        assertEquals(scancode, strokes.charAt(0));
        assertEquals(action, strokes.charAt(1));
    }
}
