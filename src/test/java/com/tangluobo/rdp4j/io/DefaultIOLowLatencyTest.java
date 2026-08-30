package com.tangluobo.rdp4j.io;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

class DefaultIOLowLatencyTest {

    @Test
    void enablesTcpNoDelayOnTheConnectedSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            DefaultIO io = new DefaultIO(InetAddress.getLoopbackAddress(), server.getLocalPort());
            try {
                io.setLowLatency(true);
                io.getInputStream();
                assertTrue(io.getSocket().getTcpNoDelay());
            } finally {
                io.closeIO();
            }
        }
    }
}
