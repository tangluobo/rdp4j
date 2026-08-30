package com.tangluobo.rdp4j;

/** Toolkit-neutral input lifecycle used by both Swing and JavaFX frontends. */
public interface RdpInput {

    void gainedFocus();

    void lostFocus();

    void triggerReadyToSend();

    void synchronizeLockKeys();

    void sendCtrlAltDel();

    default void dispose() {
    }
}
