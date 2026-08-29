package com.tangluobo.tomato.rdp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.rdp5.VChannel;
import com.tangluobo.tomato.rdp.rdp5.VChannels;

/**
 * Minimal, protocol-complete RDPDR static-channel endpoint.
 *
 * Windows uses the presence of the RDPDR channel as a prerequisite for
 * starting the RDPSND server endpoint. Device redirection itself is not
 * enabled, but the core announce/capability handshake still has to complete;
 * merely advertising the channel and dropping the Server Announce packet leaves
 * the Windows redirector in its initial state.
 */
final class RdpdrChannel extends VChannel {

    private static final Logger logger = Logger.getLogger(RdpdrChannel.class.getName());

    private static final int RDPDR_CTYP_CORE = 0x4472;
    private static final int PAKID_CORE_SERVER_ANNOUNCE = 0x496E;
    private static final int PAKID_CORE_CLIENTID_CONFIRM = 0x4343;
    private static final int PAKID_CORE_CLIENT_NAME = 0x434E;
    private static final int PAKID_CORE_DEVICELIST_ANNOUNCE = 0x4441;
    private static final int PAKID_CORE_SERVER_CAPABILITY = 0x5350;
    private static final int PAKID_CORE_CLIENT_CAPABILITY = 0x4350;
    private static final int PAKID_CORE_USER_LOGGEDON = 0x554C;

    private static final int CLIENT_VERSION_MAJOR = 1;
    private static final int CLIENT_VERSION_MINOR = 0x000D;

    private boolean serverCapabilitiesReceived;
    private boolean clientIdConfirmed;
    private boolean emptyDeviceListSent;

    @Override
    public String name() {
        return "rdpdr";
    }

    @Override
    public int flags() {
        return VChannels.CHANNEL_OPTION_INITIALIZED
                | VChannels.CHANNEL_OPTION_ENCRYPT_RDP
                | VChannels.CHANNEL_OPTION_COMPRESS_RDP;
    }

    @Override
    public void process(Packet data) throws RdesktopException, IOException {
        int available = data.getEnd() - data.getPosition();
        if (available < 4) {
            logger.warning("rdpdr: truncated packet, bytes=" + available);
            return;
        }

        int start = data.getPosition();
        int component = data.getLittleEndian16();
        int packetId = data.getLittleEndian16();
        if (component != RDPDR_CTYP_CORE) {
            logger.fine("rdpdr: ignoring non-core component=0x"
                    + Integer.toHexString(component));
            return;
        }

        switch (packetId) {
            case PAKID_CORE_SERVER_ANNOUNCE:
                processServerAnnounce(data, available - 4);
                break;
            case PAKID_CORE_SERVER_CAPABILITY:
                sendClientCapabilities();
                serverCapabilitiesReceived = true;
                maybeSendEmptyDeviceList();
                break;
            case PAKID_CORE_CLIENTID_CONFIRM:
                clientIdConfirmed = true;
                logger.info("rdpdr: 服务端已确认ClientId");
                maybeSendEmptyDeviceList();
                break;
            case PAKID_CORE_USER_LOGGEDON:
                // Supporting USER_LOGGEDON is deliberately not advertised, but
                // tolerate it and finish the no-device announce if received.
                sendEmptyDeviceList();
                break;
            default:
                logger.fine("rdpdr: ignored core packet id=0x"
                        + Integer.toHexString(packetId) + ", bytes=" + available);
                break;
        }
        data.setPosition(start);
    }

    private void processServerAnnounce(Packet data, int bodyLength)
            throws RdesktopException, IOException {
        if (bodyLength < 8) {
            logger.warning("rdpdr: Server Announce packet too short");
            return;
        }
        int serverMajor = data.getLittleEndian16();
        int serverMinor = data.getLittleEndian16();
        int clientId = data.getLittleEndian32();

        // VersionMinor < 12 requires a newly generated id. Current Windows
        // advertises 13; use a stable nonzero id for legacy servers.
        if (serverMinor < 0x000C) {
            clientId = 0x544F4D41; // "TOMA"
        }
        serverCapabilitiesReceived = false;
        clientIdConfirmed = false;
        emptyDeviceListSent = false;

        sendClientAnnounce(clientId);
        sendClientName();
        logger.info("rdpdr: 初始化应答已发送，serverVersion=" + serverMajor
                + "." + serverMinor + ", clientId=0x"
                + Integer.toHexString(clientId));
    }

    private void sendClientAnnounce(int clientId) throws RdesktopException, IOException {
        Packet p = new Packet(12);
        writeHeader(p, PAKID_CORE_CLIENTID_CONFIRM);
        p.setLittleEndian16(CLIENT_VERSION_MAJOR);
        p.setLittleEndian16(CLIENT_VERSION_MINOR);
        p.setLittleEndian32(clientId);
        p.markEnd();
        send_packet(p);
    }

    private void sendClientName() throws RdesktopException, IOException {
        byte[] name = "TOMATO\0".getBytes(StandardCharsets.UTF_16LE);
        Packet p = new Packet(16 + name.length);
        writeHeader(p, PAKID_CORE_CLIENT_NAME);
        p.setLittleEndian32(1); // UnicodeFlag
        p.setLittleEndian32(0); // CodePage
        p.setLittleEndian32(name.length);
        p.copyFromByteArray(name, 0, p.getPosition(), name.length);
        p.incrementPosition(name.length);
        p.markEnd();
        send_packet(p);
    }

    private void sendClientCapabilities() throws RdesktopException, IOException {
        // Capability sets are optional. This channel redirects no devices, so a
        // valid zero-set response is preferable to falsely advertising support.
        Packet p = new Packet(8);
        writeHeader(p, PAKID_CORE_CLIENT_CAPABILITY);
        p.setLittleEndian16(0); // numCapabilities
        p.setLittleEndian16(0); // padding
        p.markEnd();
        send_packet(p);
        logger.info("rdpdr: Client Core Capability Response已发送（无设备能力）");
    }

    private void maybeSendEmptyDeviceList() throws RdesktopException, IOException {
        if (serverCapabilitiesReceived && clientIdConfirmed) {
            sendEmptyDeviceList();
        }
    }

    private void sendEmptyDeviceList() throws RdesktopException, IOException {
        if (emptyDeviceListSent) {
            return;
        }
        Packet p = new Packet(8);
        writeHeader(p, PAKID_CORE_DEVICELIST_ANNOUNCE);
        p.setLittleEndian32(0); // DeviceCount: this channel is handshake-only
        p.markEnd();
        send_packet(p);
        emptyDeviceListSent = true;
        logger.info("rdpdr: 空设备列表已发送，核心初始化完成");
    }

    private static void writeHeader(Packet p, int packetId) {
        p.setLittleEndian16(RDPDR_CTYP_CORE);
        p.setLittleEndian16(packetId);
    }
}
