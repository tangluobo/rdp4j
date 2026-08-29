package com.tangluobo.tomato.rdp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed MS-RDPBCGR Server Redirection Packet. */
public final class RdpRedirectionInfo {

    public static final int LB_TARGET_NET_ADDRESS = 0x00000001;
    public static final int LB_LOAD_BALANCE_INFO = 0x00000002;
    public static final int LB_USERNAME = 0x00000004;
    public static final int LB_DOMAIN = 0x00000008;
    public static final int LB_PASSWORD = 0x00000010;
    public static final int LB_NOREDIRECT = 0x00000080;
    public static final int LB_TARGET_FQDN = 0x00000100;
    public static final int LB_TARGET_NETBIOS_NAME = 0x00000200;
    public static final int LB_TARGET_NET_ADDRESSES = 0x00000800;
    public static final int LB_CLIENT_TSV_URL = 0x00001000;
    public static final int LB_PASSWORD_IS_PK_ENCRYPTED = 0x00004000;
    public static final int LB_REDIRECTION_GUID = 0x00008000;
    public static final int LB_TARGET_CERTIFICATE = 0x00010000;

    private final int sessionId;
    private final int flags;
    private String targetNetAddress;
    private byte[] loadBalanceInfo;
    private String username;
    private String domain;
    private byte[] password;
    private String targetFqdn;
    private String targetNetbiosName;
    private byte[] tsvUrl;
    private byte[] redirectionGuid;
    private byte[] targetCertificate;
    private final List<String> targetNetAddresses = new ArrayList<>();

    public RdpRedirectionInfo(int sessionId, int flags) {
        this.sessionId = sessionId;
        this.flags = flags;
    }

    public int getSessionId() { return sessionId; }
    public int getFlags() { return flags; }
    public boolean hasFlag(int flag) { return (flags & flag) != 0; }
    public boolean isNoRedirect() { return hasFlag(LB_NOREDIRECT); }
    public boolean isPasswordPkEncrypted() { return hasFlag(LB_PASSWORD_IS_PK_ENCRYPTED); }

    public String getTargetNetAddress() { return targetNetAddress; }
    public void setTargetNetAddress(String value) { targetNetAddress = value; }
    public byte[] getLoadBalanceInfo() { return copy(loadBalanceInfo); }
    public void setLoadBalanceInfo(byte[] value) { loadBalanceInfo = copy(value); }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getDomain() { return domain; }
    public void setDomain(String value) { domain = value; }
    public byte[] getPassword() { return copy(password); }
    public void setPassword(byte[] value) { password = copy(value); }
    public String getTargetFqdn() { return targetFqdn; }
    public void setTargetFqdn(String value) { targetFqdn = value; }
    public String getTargetNetbiosName() { return targetNetbiosName; }
    public void setTargetNetbiosName(String value) { targetNetbiosName = value; }
    public byte[] getTsvUrl() { return copy(tsvUrl); }
    public void setTsvUrl(byte[] value) { tsvUrl = copy(value); }
    public byte[] getRedirectionGuid() { return copy(redirectionGuid); }
    public void setRedirectionGuid(byte[] value) { redirectionGuid = copy(value); }
    public byte[] getTargetCertificate() { return copy(targetCertificate); }
    public void setTargetCertificate(byte[] value) { targetCertificate = copy(value); }
    public List<String> getTargetNetAddresses() {
        return Collections.unmodifiableList(targetNetAddresses);
    }
    public void addTargetNetAddress(String value) { targetNetAddresses.add(value); }

    /**
     * Returns the clear-text redirect password. Encrypted RDSTLS credentials
     * deliberately remain opaque and are never converted or logged.
     */
    public char[] getClearTextPassword() throws RdesktopException {
        if (isPasswordPkEncrypted()) {
            throw new RdesktopException("重定向密码采用RDSTLS加密格式");
        }
        if (password == null || password.length == 0) {
            return new char[0];
        }
        if ((password.length & 1) != 0) {
            throw new RdesktopException("重定向密码的UTF-16LE长度无效");
        }
        String decoded = new String(password, StandardCharsets.UTF_16LE);
        int terminator = decoded.indexOf('\0');
        if (terminator >= 0) {
            decoded = decoded.substring(0, terminator);
        }
        return decoded.toCharArray();
    }

    /** Selects a target only when the packet does not use an opaque routing token. */
    public String selectTargetHost(String currentHost) {
        if (loadBalanceInfo != null && loadBalanceInfo.length > 0) {
            return currentHost;
        }
        if (targetFqdn != null && !targetFqdn.isBlank()) return targetFqdn;
        if (targetNetAddress != null && !targetNetAddress.isBlank()) return targetNetAddress;
        if (targetNetbiosName != null && !targetNetbiosName.isBlank()) return targetNetbiosName;
        if (!targetNetAddresses.isEmpty()) return targetNetAddresses.get(0);
        return currentHost;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
