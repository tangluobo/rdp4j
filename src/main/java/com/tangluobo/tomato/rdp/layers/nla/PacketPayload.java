package com.tangluobo.tomato.rdp.layers.nla;

import java.io.IOException;

import com.tangluobo.tomato.rdp.Packet;

interface PacketPayload {
	Packet write() throws IOException;
	
	void read(Packet packet) throws IOException;
}