package com.tangluobo.rdp4j.layers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

import com.tangluobo.rdp4j.Options;
import com.tangluobo.rdp4j.State;
import com.tangluobo.rdp4j.io.IO;

class TransportLowLatencyTest {

    @Test
    void appliesInteractiveLatencyPreferenceBeforeOpeningStreams() throws Exception {
        Options options = new Options();
        options.setLowLatency(true);
        RecordingIO io = new RecordingIO();

        new Transport(new State(options), null).connect(io);

        assertTrue(io.lowLatencyConfigured);
        assertTrue(io.streamOpenedAfterConfiguration);
    }

    private static final class RecordingIO implements IO {
        boolean lowLatencyConfigured;
        boolean streamOpenedAfterConfiguration;

        @Override
        public void setLowLatency(boolean lowLatency) {
            lowLatencyConfigured = lowLatency;
        }

        @Override
        public InputStream getInputStream() {
            streamOpenedAfterConfiguration = lowLatencyConfigured;
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            streamOpenedAfterConfiguration &= lowLatencyConfigured;
            return new ByteArrayOutputStream();
        }

        @Override public void closeIO() throws IOException { }
        @Override public byte[] getPublicKey() { return new byte[0]; }
        @Override public String getAddress() { return "127.0.0.1"; }
    }
}
