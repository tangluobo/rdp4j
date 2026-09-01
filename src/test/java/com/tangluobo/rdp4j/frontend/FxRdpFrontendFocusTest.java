package com.tangluobo.rdp4j.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class FxRdpFrontendFocusTest {

    @Test
    void forwardsJavaFxDesktopFocusToClipboardLifecycle() {
        FxRdpFrontend frontend = new FxRdpFrontend();
        AtomicInteger notifications = new AtomicInteger();
        frontend.setFocusGainedListener(notifications::incrementAndGet);

        frontend.notifyFocusGained();

        assertEquals(1, notifications.get());
    }
}
