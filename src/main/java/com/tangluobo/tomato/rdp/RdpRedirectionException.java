package com.tangluobo.tomato.rdp;

/** Internal control-flow exception used to reconnect after a Server Redirection PDU. */
public final class RdpRedirectionException extends RdesktopException {
    private static final long serialVersionUID = 1L;

    private final RdpRedirectionInfo redirection;

    public RdpRedirectionException(RdpRedirectionInfo redirection) {
        super(buildMessage(redirection));
        this.redirection = redirection;
    }

    public RdpRedirectionInfo getRedirection() {
        return redirection;
    }

    private static String buildMessage(RdpRedirectionInfo value) {
        String target = value.getTargetFqdn();
        if (target == null || target.isBlank()) target = value.getTargetNetAddress();
        if (target == null || target.isBlank()) target = "当前服务器（routing token）";
        return "服务端要求RDP会话重定向: target=" + target
                + ", sessionId=" + Integer.toUnsignedString(value.getSessionId())
                + ", flags=0x" + Integer.toHexString(value.getFlags())
                + ", rdstls=" + value.isPasswordPkEncrypted();
    }
}
