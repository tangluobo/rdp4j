package com.tangluobo.rdp4j.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.Packet;
import com.tangluobo.rdp4j.rdp5.cliprdr.ClipInterface;

class FixedUnicodeHandlerTest {

    @Test
    void decodesRemoteUtf16TextIntoLocalClipboardTransferable() throws Exception {
        String expected = "远程 clipboard 文本";
        byte[] encoded = (expected + "\0").getBytes(StandardCharsets.UTF_16LE);
        Packet packet = new Packet(encoded.length);
        packet.copyFromByteArray(encoded, 0, 0, encoded.length);
        packet.markEnd();
        packet.setPosition(0);
        AtomicReference<Transferable> copied = new AtomicReference<>();

        new FixedUnicodeHandler().handleData(packet, encoded.length, new RecordingClip(copied));

        assertEquals(expected, copied.get().getTransferData(DataFlavor.stringFlavor));
    }

    private record RecordingClip(AtomicReference<Transferable> copied) implements ClipInterface {
        @Override
        public void copyToClipboard(Transferable transferable) {
            copied.set(transferable);
        }

        @Override
        public void send_data(byte[] data, int length) {
        }

        @Override
        public void send_null(int type, int status) {
        }
    }
}
