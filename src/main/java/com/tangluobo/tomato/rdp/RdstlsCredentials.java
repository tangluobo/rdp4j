package com.tangluobo.tomato.rdp;

/**
 * Opaque one-time credentials carried by an RDSTLS Server Redirection PDU.
 *
 * <p>The password is intentionally kept as bytes: with
 * {@code LB_PASSWORD_IS_PK_ENCRYPTED} it is a target-server encrypted blob,
 * not a UTF-16 password and must never be decoded or logged.</p>
 */
public final class RdstlsCredentials {
    private final String domain;
    private final String username;
    private final byte[] redirectionGuid;
    private final byte[] encryptedPassword;

    public RdstlsCredentials(String domain, String username,
                             byte[] redirectionGuid, byte[] encryptedPassword) {
        this.domain = domain == null ? "" : domain;
        this.username = username == null ? "" : username;
        this.redirectionGuid = copy(redirectionGuid);
        this.encryptedPassword = copy(encryptedPassword);
    }

    public String getDomain() {
        return domain;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getRedirectionGuid() {
        return copy(redirectionGuid);
    }

    public byte[] getEncryptedPassword() {
        return copy(encryptedPassword);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : value.clone();
    }
}
