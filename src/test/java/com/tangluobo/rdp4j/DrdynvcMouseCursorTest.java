package com.tangluobo.rdp4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.graphics.RdesktopCanvas;
import com.tangluobo.rdp4j.graphics.RdpCursor;
import com.tangluobo.rdp4j.rdp5.VChannels;

class DrdynvcMouseCursorTest {

    @Test
    void advertisesVersionOneMouseCursorCapability() {
        byte[] payload = DrdynvcChannel.createMouseCursorCapabilities();

        assertEquals(16, payload.length);
        assertEquals(1, payload[0] & 0xff);
        assertEquals(0, payload[1] & 0xff);
        assertEquals(0x53504143L, u32(payload, 4));
        assertEquals(1, u32(payload, 8));
        assertEquals(12, u32(payload, 12));
    }

    @Test
    void decodesLargePointerWithoutScalingOrLosingAlpha() throws Exception {
        Options options = new Options();
        options.setWidth(8);
        options.setHeight(8);
        State state = new State(options);
        new RdesktopCanvas(new NoOpContext(), state);
        DrdynvcChannel channel = new DrdynvcChannel();
        channel.start(null, state, null);

        byte[] payload = new byte[4 + 20 + 16 + 4];
        payload[0] = 3; // MOUSEPTR_UPDATE
        payload[1] = 0x0c; // LARGE_POINTER
        putU16(payload, 4, 32);
        putU16(payload, 6, 7);
        putU16(payload, 8, 1);
        putU16(payload, 10, 0);
        putU16(payload, 12, 2);
        putU16(payload, 14, 2);
        putU32(payload, 16, 4); // AND mask length
        putU32(payload, 20, 16); // XOR mask length

        // RDP transmits multi-byte cursor scan lines bottom-up in BGRA order.
        int p = 24;
        p = putBgra(payload, p, 255, 0, 0, 255); // bottom-left blue
        p = putBgra(payload, p, 0, 255, 0, 255); // bottom-right green
        p = putBgra(payload, p, 0, 0, 0, 0); // top-left transparent
        p = putBgra(payload, p, 255, 255, 255, 128); // top-right antialiased white
        // Two padded bytes per AND-mask row, both fully opaque.
        payload[p] = 0;
        payload[p + 1] = 0;
        payload[p + 2] = 0;
        payload[p + 3] = 0;

        channel.processMouseCursorData(payload);

        RdpCursor cursor = state.getCache().getCursor(7);
        BufferedImage image = (BufferedImage) cursor.getData();
        assertEquals(2, image.getWidth(), "large pointer source width must not be rescaled");
        assertEquals(2, image.getHeight(), "large pointer source height must not be rescaled");
        assertEquals(0x00000000, image.getRGB(0, 0));
        assertEquals(0x80ffffff, image.getRGB(1, 0), "antialiased alpha must survive the DVC path");
        assertEquals(0xff0000ff, image.getRGB(0, 1));
        assertEquals(0xff00ff00, image.getRGB(1, 1));
        assertEquals(1, cursor.getHotspot().x);
        assertEquals(0, cursor.getHotspot().y);
    }

    @Test
    void reassemblesFragmentedFastPathPointerBeforeDecoding() throws Exception {
        Options options = new Options();
        options.setWidth(8);
        options.setHeight(8);
        State state = new State(options);
        new RdesktopCanvas(new NoOpContext(), state);
        RdpPatch rdp = new RdpPatch(new NoOpContext(), state, new VChannels(state));

        byte[] pointer = new byte[20 + 16 + 4];
        putU16(pointer, 0, 32);
        putU16(pointer, 2, 5);
        putU16(pointer, 4, 0);
        putU16(pointer, 6, 1);
        putU16(pointer, 8, 2);
        putU16(pointer, 10, 2);
        putU32(pointer, 12, 4);
        putU32(pointer, 16, 16);
        int p = 20;
        p = putBgra(pointer, p, 255, 0, 0, 255);
        p = putBgra(pointer, p, 0, 255, 0, 255);
        p = putBgra(pointer, p, 0, 0, 255, 255);
        p = putBgra(pointer, p, 255, 255, 255, 128);

        rdp.rdp5_process(fastPathFragment(12, 2, pointer, 0, 7), false, false);
        rdp.rdp5_process(fastPathFragment(12, 3, pointer, 7, 15), false, false);
        rdp.rdp5_process(fastPathFragment(12, 1, pointer, 22, pointer.length - 22), false, false);

        RdpCursor cursor = state.getCache().getCursor(5);
        BufferedImage image = (BufferedImage) cursor.getData();
        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0x80ffffff, image.getRGB(1, 0));
        assertEquals(0, cursor.getHotspot().x);
        assertEquals(1, cursor.getHotspot().y);
    }

    private static Packet fastPathFragment(int type, int fragmentation,
            byte[] data, int offset, int length) {
        byte[] packet = new byte[3 + length];
        packet[0] = (byte) (type | (fragmentation << 4));
        putU16(packet, 1, length);
        System.arraycopy(data, offset, packet, 3, length);
        Packet result = new Packet(packet);
        result.setPosition(0);
        return result;
    }

    private static int putBgra(byte[] data, int offset, int blue, int green, int red, int alpha) {
        data[offset] = (byte) blue;
        data[offset + 1] = (byte) green;
        data[offset + 2] = (byte) red;
        data[offset + 3] = (byte) alpha;
        return offset + 4;
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    private static final class NoOpContext implements IContext {
        @Override public void dispose() { }
        @Override public void error(Exception e, boolean dispose) { }
        @Override public byte[] loadLicense() throws IOException { return null; }
        @Override public void saveLicense(byte[] license) throws IOException { }
        @Override public void screenResized(int width, int height, boolean clientInitiated) { }
        @Override public void setLoggedOn() { }
        @Override public void toggleFullScreen() { }
        @Override public void ready(ReadyType ready) { }
    }
}
