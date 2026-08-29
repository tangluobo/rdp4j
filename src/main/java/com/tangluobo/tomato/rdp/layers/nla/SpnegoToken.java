package com.tangluobo.tomato.rdp.layers.nla;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal DER codec for the NTLM-only SPNEGO exchange used by CredSSP.
 *
 * <p>This deliberately implements only the small RFC 4178 subset required by
 * CredSSP: NegTokenInit with the NTLM mechanism and NegTokenResp with an NTLM
 * response token and optional mechListMIC. It has no native dependencies.</p>
 */
final class SpnegoToken {
	private static final byte[] NTLM_SIGNATURE = {
			'N', 'T', 'L', 'M', 'S', 'S', 'P', 0
	};
	private static final byte[] SPNEGO_OID = {
			0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02
	};
	private static final byte[] NTLM_OID = {
			0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01,
			(byte) 0x82, 0x37, 0x02, 0x02, 0x0a
	};
	private static final byte[] NTLM_MECH_TYPES = tlv(0x30, NTLM_OID);

	private SpnegoToken() {
	}

	static byte[] initial(byte[] ntlmNegotiate) {
		byte[] mechTypes = tlv(0xa0, NTLM_MECH_TYPES);
		byte[] mechToken = tlv(0xa2, tlv(0x04, ntlmNegotiate));
		byte[] negTokenInit = tlv(0xa0, tlv(0x30, concat(mechTypes, mechToken)));
		return tlv(0x60, concat(SPNEGO_OID, negTokenInit));
	}

	static byte[] response(byte[] ntlmAuthenticate, byte[] mechListMic) {
		// Windows emits accept-incomplete while sending the final NTLM token;
		// the acceptor completes the context after verifying this message.
		byte[] state = tlv(0xa0, new byte[] { 0x0a, 0x01, 0x01 });
		byte[] responseToken = tlv(0xa2, tlv(0x04, ntlmAuthenticate));
		byte[] mic = mechListMic == null ? new byte[0]
				: tlv(0xa3, tlv(0x04, mechListMic));
		return tlv(0xa1, tlv(0x30, concat(state, responseToken, mic)));
	}

	static byte[] mechTypes() {
		return NTLM_MECH_TYPES.clone();
	}

	static Parsed parse(byte[] token) throws IOException {
		if (startsWith(token, NTLM_SIGNATURE)) {
			return new Parsed(false, token, null);
		}
		DerElement top = read(token, 0);
		if (top.end != token.length || (top.tag != 0x60 && top.tag != 0xa1 && top.tag != 0xa0)) {
			throw new IOException("Unsupported SPNEGO token.");
		}
		Fields fields = new Fields();
		collect(token, top.contentOffset, top.end, fields);
		if (fields.responseToken != null && !startsWith(fields.responseToken, NTLM_SIGNATURE)) {
			throw new IOException("SPNEGO selected a mechanism other than NTLM.");
		}
		return new Parsed(true, fields.responseToken, fields.mechListMic);
	}

	private static void collect(byte[] data, int offset, int end, Fields fields) throws IOException {
		int position = offset;
		while (position < end) {
			DerElement element = read(data, position);
			if (element.end > end)
				throw new IOException("SPNEGO element exceeds its parent.");
			if (element.tag == 0xa2 || element.tag == 0xa3) {
				DerElement child = read(data, element.contentOffset);
				if (child.end != element.end || child.tag != 0x04)
					throw new IOException("Invalid SPNEGO OCTET STRING field.");
				byte[] value = Arrays.copyOfRange(data, child.contentOffset, child.end);
				if (element.tag == 0xa2)
					fields.responseToken = value;
				else
					fields.mechListMic = value;
			} else if ((element.tag & 0x20) != 0) {
				collect(data, element.contentOffset, element.end, fields);
			}
			position = element.end;
		}
		if (position != end)
			throw new IOException("Invalid SPNEGO DER length.");
	}

	private static DerElement read(byte[] data, int offset) throws IOException {
		if (data == null || offset < 0 || offset >= data.length)
			throw new IOException("Truncated SPNEGO token.");
		int tag = data[offset] & 0xff;
		int position = offset + 1;
		if (position >= data.length)
			throw new IOException("Truncated SPNEGO length.");
		int first = data[position++] & 0xff;
		int length;
		if ((first & 0x80) == 0) {
			length = first;
		} else {
			int count = first & 0x7f;
			if (count == 0 || count > 4 || position + count > data.length)
				throw new IOException("Invalid SPNEGO DER length.");
			length = 0;
			for (int i = 0; i < count; i++)
				length = (length << 8) | (data[position++] & 0xff);
		}
		long end = (long) position + length;
		if (end > data.length)
			throw new IOException("Truncated SPNEGO value.");
		return new DerElement(tag, position, (int) end);
	}

	private static byte[] tlv(int tag, byte[] value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
		out.write(tag);
		writeLength(out, value.length);
		out.writeBytes(value);
		return out.toByteArray();
	}

	private static void writeLength(ByteArrayOutputStream out, int length) {
		if (length < 0x80) {
			out.write(length);
		} else if (length <= 0xff) {
			out.write(0x81);
			out.write(length);
		} else if (length <= 0xffff) {
			out.write(0x82);
			out.write(length >>> 8);
			out.write(length);
		} else {
			out.write(0x83);
			out.write(length >>> 16);
			out.write(length >>> 8);
			out.write(length);
		}
	}

	private static byte[] concat(byte[]... values) {
		int length = 0;
		for (byte[] value : values)
			length += value.length;
		byte[] result = new byte[length];
		int offset = 0;
		for (byte[] value : values) {
			System.arraycopy(value, 0, result, offset, value.length);
			offset += value.length;
		}
		return result;
	}

	private static boolean startsWith(byte[] value, byte[] prefix) {
		if (value == null || value.length < prefix.length)
			return false;
		for (int i = 0; i < prefix.length; i++) {
			if (value[i] != prefix[i])
				return false;
		}
		return true;
	}

	static final class Parsed {
		private final boolean wrapped;
		private final byte[] responseToken;
		private final byte[] mechListMic;

		Parsed(boolean wrapped, byte[] responseToken, byte[] mechListMic) {
			this.wrapped = wrapped;
			this.responseToken = responseToken;
			this.mechListMic = mechListMic;
		}

		boolean isWrapped() {
			return wrapped;
		}

		byte[] getResponseToken() {
			return responseToken;
		}

		byte[] getMechListMic() {
			return mechListMic;
		}
	}

	private static final class Fields {
		private byte[] responseToken;
		private byte[] mechListMic;
	}

	private record DerElement(int tag, int contentOffset, int end) {
	}
}
