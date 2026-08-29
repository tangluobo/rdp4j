package com.tangluobo.tomato.rdp.layers.nla;

import java.io.IOException;

import com.tangluobo.tomato.rdp.jasn1.ber.types.BerType;

interface BerPayload {
	BerType write() throws IOException;
}