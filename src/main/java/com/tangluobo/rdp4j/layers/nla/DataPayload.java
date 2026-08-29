package com.tangluobo.rdp4j.layers.nla;

import java.io.IOException;

interface DataPayload {
	byte[] write() throws IOException;
}