package com.tangluobo.rdp4j.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class DefaultIO implements IO {
	private InetAddress address;
	private int port;
	private Socket socket;
	private boolean lowLatency;

	public DefaultIO(InetAddress server, int port) {
		this.address = server;
		this.port = port;
	}

	@Override
	public void closeIO() throws IOException {
		Socket current = socket;
		if (current == null) return;
		try {
			current.close();
		} finally {
			socket = null;
		}
	}

	@Override
	public void setLowLatency(boolean lowLatency) throws IOException {
		this.lowLatency = lowLatency;
		if (socket != null) {
			socket.setTcpNoDelay(lowLatency);
		}
	}

	@Override
	public InputStream getInputStream() throws IOException {
		checkConnected();
		return socket.getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		checkConnected();
		return socket.getOutputStream();
	}

	public Socket getSocket() {
		return socket;
	}

	@Override
	public byte[] getPublicKey() {
		return new byte[0];
	}

	void checkConnected() throws IOException {
		if (socket == null) {
			Socket candidate = new Socket();
			try {
				candidate.setTcpNoDelay(lowLatency);
				candidate.connect(new InetSocketAddress(address, port));
				socket = candidate;
			} catch (IOException | RuntimeException error) {
				try {
					candidate.close();
				} catch (IOException ignored) {
				}
				throw error;
			}
		}
	}

	@Override
	public String getAddress() {
		return address.getHostAddress();
	}
}
