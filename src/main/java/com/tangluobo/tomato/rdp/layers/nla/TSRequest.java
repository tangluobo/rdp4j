package com.tangluobo.tomato.rdp.layers.nla;

import java.io.IOException;

import com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerInteger;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerOctetString;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerSequence;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TSRequest implements BerPayload {
	static Logger logger = LoggerFactory.getLogger(TSRequest.class);
	
	private int version = 6;
	private byte[] negoData = null;
	private byte[] authInfo = null;
	private byte[] pubKeyAuth = null;
	private Integer errorCode = null;
	private byte[] clientNonce = null;

	public TSRequest(byte[] negoData) {
		this.negoData = negoData;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public byte[] getNegoData() {
		return negoData;
	}

	public void setNegoData(byte[] negoData) {
		this.negoData = negoData;
	}

	public byte[] getAuthInfo() {
		return authInfo;
	}

	public void setAuthInfo(byte[] authInfo) {
		this.authInfo = authInfo;
	}

	public byte[] getPubKeyAuth() {
		return pubKeyAuth;
	}

	public void setPubKeyAuth(byte[] pubKeyAuth) {
		this.pubKeyAuth = pubKeyAuth;
	}

	public Integer getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(Integer errorCode) {
		this.errorCode = errorCode;
	}

	public byte[] getClientNonce() {
		return clientNonce;
	}

	public void setClientNonce(byte[] clientNonce) {
		this.clientNonce = clientNonce;
	}

	public void read(BerType type) throws IOException {
		BerSequence seq = (BerSequence) type;
		BerInteger errBer = seq.get(BerInteger.class, 4);
		if (errBer != null) {
			throw new HResultException(errBer.intValue());
		}
		version = seq.get(BerInteger.class, 0).intValue();
		BerSequence negoTokens = seq.get(BerSequence.class, 1);
		if (negoTokens != null) {
			BerSequence negoSeqs = negoTokens.get(BerSequence.class, 0);
			BerOctetString negoToken = negoSeqs.get(BerOctetString.class, 0);
			negoData = negoToken.value;
		} else {
			negoData = null;
		}
		BerOctetString auth = seq.get(BerOctetString.class, 2);
		authInfo = auth == null ? null : auth.value;
		BerOctetString pubKey = seq.get(BerOctetString.class, 3);
		pubKeyAuth = pubKey == null ? null : pubKey.value;
		BerOctetString nonce = seq.get(BerOctetString.class, 5);
		clientNonce = nonce == null ? null : nonce.value;
	}

	@Override
	public BerType write() throws IOException {
		BerSequence seq = new BerSequence();
		seq.add(new BerContextSpecific(new BerInteger(version), 0));
		if (negoData != null) {
			/*
			 * A NegoData structure, as defined in section 2.2.1.1, that
			 * contains the SPNEGO tokens or Kerberos/NTLM messages that are
			 * passed between the client and server.
			 */
			BerOctetString negoToken = new BerOctetString(negoData);
			BerContextSpecific negoSeq = new BerContextSpecific(negoToken, 0);
			BerSequence negoSeqs = new BerSequence(negoSeq);
			BerSequence negoTokens = new BerSequence(negoSeqs);
			logger.info(String.format("Added SPNEGO structure"));
			seq.add(new BerContextSpecific(negoTokens, 1));
		}
		if (authInfo != null) {
			/*
			 * A TSCredentials structure, as defined in section 2.2.1.2, that
			 * contains the user's credentials that are delegated to the server.
			 * The authInfo field MUST be encrypted under the encryption key
			 * that is negotiated under the SPNEGO package. The authInfo field
			 * carries the message signature and then the encrypted data.
			 */
			logger.info(String.format("Added TSCredentials structure"));
			seq.add(new BerContextSpecific(new BerOctetString(authInfo), 2));
		}
		/*
		 * pubKeyAuth: This field is used to assure that the public key that is
		 * used by the server during the TLS handshake belongs to the target
		 * server and not to a "man in the middle". This TLS session-binding is
		 * described in section 3.1.5. After the client completes the SPNEGO
		 * phase of the CredSSP Protocol, it uses GSS_WrapEx() for the
		 * negotiated protocol to encrypt the server's public key. The
		 * pubKeyAuth field carries the message signature and then the encrypted
		 * public key to the server. In response, the server uses the pubKeyAuth
		 * field to transmit to the client a modified version of the public key
		 * (as described in section 3.1.5) that is encrypted under the
		 * encryption key that is negotiated under SPNEGO.
		 */
		if (pubKeyAuth != null) {
			logger.info(String.format("Added pubkeyAuth structure"));
			seq.add(new BerContextSpecific(new BerOctetString(pubKeyAuth), 3));
		}
		/*
		 * If the negotiated protocol version is 3 and the SPNEGO exchange fails
		 * on the server, this field can be used to send the NTSTATUS failure
		 * code ([MS-ERREF] section 2.3) to the client so that it will know what
		 * failed and be able to display a descriptive error to the user.<9>
		 */
		if (errorCode != null) {
			logger.info(String.format("Added errorCode structure"));
			seq.add(new BerContextSpecific(new BerInteger(errorCode), 4));
		}
		if (clientNonce != null) {
			if (clientNonce.length != 32) {
				throw new IOException("CredSSP clientNonce must be exactly 32 bytes.");
			}
			seq.add(new BerContextSpecific(new BerOctetString(clientNonce), 5));
		}
		return seq;
	}
}
