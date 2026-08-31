package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class WindowsFullScreenKeyboardHookTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void installsLowLevelKeyboardHook() {
        WindowsFullScreenKeyboardHook hook = WindowsFullScreenKeyboardHook.instance();
        WindowsFullScreenKeyboardHook.KeySink sink = (scanCode, extended, release) -> true;

        assertTrue(hook.activate(sink));
        assertTrue(hook.isAvailable());
        hook.deactivate(sink);
    }
}
