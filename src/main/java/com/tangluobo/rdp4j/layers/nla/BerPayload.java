package com.tangluobo.rdp4j.layers.nla;

import java.io.IOException;

import com.tangluobo.rdp4j.jasn1.ber.types.BerType;

interface BerPayload {
	BerType write() throws IOException;
}