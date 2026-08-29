package com.tangluobo.rdp4j.layers.nla;

import java.io.IOException;

import com.tangluobo.rdp4j.Packet;

interface PacketPayload {
	Packet write() throws IOException;
	
	void read(Packet packet) throws IOException;
}