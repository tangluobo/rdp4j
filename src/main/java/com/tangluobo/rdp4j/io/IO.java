package com.tangluobo.rdp4j.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface IO {
	void closeIO() throws IOException;

	/**
	 * Configure the transport for interactive traffic before its streams are opened.
	 * Implementations without a socket can safely ignore this hint.
	 */
	default void setLowLatency(boolean lowLatency) throws IOException {
	}

	InputStream getInputStream() throws IOException;

	OutputStream getOutputStream() throws IOException;

	byte[] getPublicKey();
	
	String getAddress();
}
