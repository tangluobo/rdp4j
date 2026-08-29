package com.tangluobo.tomato.rdp.layers.nla;

import java.io.IOException;
import java.util.Arrays;

import com.tangluobo.tomato.rdp.Packet;

public class NTLMSingleHost implements PacketPayload {
	public static final int LENGTH = 48;
	private byte[] customData;
	private byte[] machineId;

	@Override
	public Packet write() throws IOException {
		Packet p = new Packet(LENGTH);
		p.setLittleEndian32(LENGTH);
		p.setLittleEndian32(0); // z4
		if (customData == null)
			p.incrementPosition(8);
		else
			p.copyFromByteArray(customData, 0, p.getPosition(), 8);
		if (machineId == null)
			p.incrementPosition(32);
		else
			p.copyFromByteArray(machineId, 0, p.getPosition(), 32);
		return p;
	}

	@Override
	public void read(Packet packet) throws IOException {
		int size = packet.getLittleEndian32();
		if (size < LENGTH)
			throw new IOException("Invalid SINGLE_HOST_DATA size: " + size);
		packet.getLittleEndian32(); // z4
		customData = new byte[8];
		packet.copyToByteArray(customData, 0, packet.getPosition(), 8);
		packet.incrementPosition(8);
		machineId = new byte[32];
		packet.copyToByteArray(machineId, 0, packet.getPosition(), 32);
		packet.incrementPosition(32);
	}

	public byte[] getCustomData() {
		return customData;
	}

	public void setCustomData(byte[] customData) {
		this.customData = customData;
	}

	public byte[] getMachineId() {
		return machineId;
	}

	public void setMachineId(byte[] machineId) {
		this.machineId = machineId;
	}

	@Override
	public String toString() {
		return "NTLMSingleHost [customData=" + Arrays.toString(customData) + ", machineId=" + Arrays.toString(machineId) + "]";
	}
}
