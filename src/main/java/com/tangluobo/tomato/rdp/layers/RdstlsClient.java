package com.tangluobo.tomato.rdp.layers;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.RdstlsCredentials;
import com.tangluobo.tomato.rdp.State;

/** Pure-Java RDSTLS v1 redirected-session authentication client. */
final class RdstlsClient {
    private static final Logger logger = LoggerFactory.getLogger(RdstlsClient.class);

    private static final int VERSION_1 = 0x0001;
    private static final int TYPE_CAPABILITIES = 0x0001;
    private static final int TYPE_AUTH_REQUEST = 0x0002;
    private static final int TYPE_AUTH_RESPONSE = 0x0004;
    private static final int DATA_CAPABILITIES = 0x0001;
    private static final int DATA_PASSWORD_CREDENTIALS = 0x0001;
    private static final int DATA_RESULT_CODE = 0x0001;

    private final State state;
    private final Transport transport;

    RdstlsClient(State state, Transport transport) {
        this.state = state;
        this.transport = transport;
    }

    void start() throws IOException, RdesktopException {
        RdstlsCredentials credentials = state.getOptions().getRdstlsCredentials();
        if (credentials == null) {
            throw new RdesktopException("RDSTLS重定向缺少一次性凭据");
        }

        DataInputStream in = transport.getIn();
        DataOutputStream out = transport.getOut();
        byte[] capabilities = new byte[8];
        in.readFully(capabilities);
        int version = u16(capabilities, 0);
        int pduType = u16(capabilities, 2);
        int dataType = u16(capabilities, 4);
        int supportedVersions = u16(capabilities, 6);
        if (version != VERSION_1 || pduType != TYPE_CAPABILITIES
                || dataType != DATA_CAPABILITIES) {
            throw new RdesktopException(String.format(
                    "RDSTLS能力PDU无效: version=0x%04x, type=0x%04x, dataType=0x%04x",
                    version, pduType, dataType));
        }
        if ((supportedVersions & VERSION_1) == 0) {
            throw new RdesktopException(String.format(
                    "服务端不支持RDSTLS v1: versions=0x%04x", supportedVersions));
        }

        byte[] request = buildPasswordRequest(credentials);
        out.write(request);
        out.flush();
        logger.info("RDSTLS v1 authentication request sent ({} bytes)", request.length);

        // version(2) + type(2) + dataType(2) + resultCode(4)
        byte[] response = new byte[10];
        in.readFully(response);
        int responseVersion = u16(response, 0);
        int responseType = u16(response, 2);
        int responseDataType = u16(response, 4);
        long resultCode = u32(response, 6);
        if (responseVersion != VERSION_1 || responseType != TYPE_AUTH_RESPONSE
                || responseDataType != DATA_RESULT_CODE) {
            throw new RdesktopException(String.format(
                    "RDSTLS认证响应无效: version=0x%04x, type=0x%04x, dataType=0x%04x",
                    responseVersion, responseType, responseDataType));
        }
        if (resultCode != 0) {
            throw new RdesktopException("RDSTLS认证被服务端拒绝: "
                    + resultDescription(resultCode));
        }
        logger.info("RDSTLS v1 redirected-session authentication completed");
    }

    private byte[] buildPasswordRequest(RdstlsCredentials credentials)
            throws RdesktopException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        writeU16(out, VERSION_1);
        writeU16(out, TYPE_AUTH_REQUEST);
        writeU16(out, DATA_PASSWORD_CREDENTIALS);
        writeData(out, credentials.getRedirectionGuid(), "redirection GUID");
        writeUnicode(out, credentials.getUsername(), "username");
        writeUnicode(out, credentials.getDomain(), "domain");
        writeData(out, credentials.getEncryptedPassword(), "encrypted password");
        return out.toByteArray();
    }

    private static void writeUnicode(ByteArrayOutputStream out, String value, String field)
            throws RdesktopException {
        byte[] text = (value + '\0').getBytes(StandardCharsets.UTF_16LE);
        writeData(out, text, field);
    }

    private static void writeData(ByteArrayOutputStream out, byte[] value, String field)
            throws RdesktopException {
        byte[] data = value == null ? new byte[0] : value;
        if (data.length > 0xffff) {
            throw new RdesktopException("RDSTLS " + field + "长度超过65535字节");
        }
        writeU16(out, data.length);
        out.writeBytes(data);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static int u16(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] value, int offset) {
        return Integer.toUnsignedLong((value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24));
    }

    private static String resultDescription(long resultCode) {
        return switch ((int) resultCode) {
            case 0x00000005 -> "访问被拒绝(0x00000005)";
            case 0x0000052e -> "用户名或一次性密码无效(0x0000052e)";
            case 0x00000530 -> "登录时间受限(0x00000530)";
            case 0x00000532 -> "密码已过期(0x00000532)";
            case 0x00000533 -> "账号已禁用(0x00000533)";
            case 0x00000773 -> "必须修改密码(0x00000773)";
            case 0x00000775 -> "账号已锁定(0x00000775)";
            default -> String.format("错误码0x%08x", resultCode);
        };
    }
}
