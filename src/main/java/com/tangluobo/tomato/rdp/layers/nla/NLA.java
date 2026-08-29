package com.tangluobo.tomato.rdp.layers.nla;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.tangluobo.tomato.rdp.jasn1.ber.BerByteArrayOutputStream;
import com.tangluobo.tomato.rdp.jasn1.ber.BerInputStream;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerInteger;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerOctetString;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerSequence;
import com.tangluobo.tomato.rdp.jasn1.ber.types.BerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tangluobo.tomato.rdp.HexDump;
import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopCryptoException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.CredSspTokenMode;
import com.tangluobo.tomato.rdp.layers.Transport;

public class NLA {
	static Logger logger = LoggerFactory.getLogger(NLA.class);
	private static final int CREDSSP_VERSION = 6;
	private static final byte[] CLIENT_TO_SERVER_MAGIC =
			"CredSSP Client-To-Server Binding Hash\0".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SERVER_TO_CLIENT_MAGIC =
			"CredSSP Server-To-Client Binding Hash\0".getBytes(StandardCharsets.US_ASCII);
	private State state;
	private Transport transport;

	interface TSCredentialType extends BerPayload {
	}

	class TSPasswordCreds implements TSCredentialType {
		private String domainName;
		private String userName;
		private char[] password;

		@Override
		public BerType write() throws IOException {
			return new BerSequence(
					new com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific(
							new BerOctetString(text(domainName)), 0),
					new com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific(
							new BerOctetString(text(userName)), 1),
					new com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific(
							new BerOctetString(text(password == null ? "" : new String(password))), 2));
		}

		private byte[] text(String value) {
			return (value == null ? "" : value).getBytes(StandardCharsets.UTF_16LE);
		}
	}

	class TSCredentials implements BerPayload {
		private int credType;
		private TSCredentialType credentials;

		@Override
		public BerType write() throws IOException {
			BerByteArrayOutputStream encoded = new BerByteArrayOutputStream();
			credentials.write().encode(encoded, true);
			return new BerSequence(
					new com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific(new BerInteger(credType), 0),
					new com.tangluobo.tomato.rdp.jasn1.ber.types.BerContextSpecific(
							new BerOctetString(encoded.getArray()), 1));
		}
	}

	public NLA(State state, Transport transport) {
		this.state = state;
		this.transport = transport;
	}

	@SuppressWarnings("resource")
	public void start() throws IOException, RdesktopCryptoException {
		
		// MS-NLMP - 1.3.1.1 NTLM Connection-Oriented Call Flow
		
		/*
		 * Over the encrypted TLS channel, the SPNEGO, Kerberos, or NTLM
		 * handshake between the client and server completes authentication and
		 * establishes an encryption key that is used by the SPNEGO
		 * confidentiality services, as specified in [RFC4178]. All SPNEGO
		 * tokens or Kerberos/NTLM messages as well as the underlying encryption
		 * algorithms are opaque to the calling application (the CredSSP client
		 * and CredSSP server). The wire protocol for SPNEGO, Kerberos, and NTLM
		 * is specified in [MS-SPNG], [MS-KILE], and [MS-NLMP], respectively.
		 * The SPNEGO tokens or Kerberos/NTLM messages exchanged between the
		 * client and the server are encapsulated in the negoTokens field of the
		 * TSRequest structure (section 2.2.1). Both the client and the server
		 * useOver the encrypted TLS channel, the SPNEGO, Kerberos, or NTLM
		 * handshake between the client and server completes authentication and
		 * establishes an encryption key that is used by the SPNEGO
		 * confidentiality services, as specified in [RFC4178]. All SPNEGO
		 * tokens or Kerberos/NTLM messages as well as the underlying encryption
		 * algorithms are opaque to the calling application (the CredSSP client
		 * and CredSSP server). The wire protocol for SPNEGO, Kerberos, and NTLM
		 * is specified in [MS-SPNG], [MS-KILE], and [MS-NLMP], respectively.
		 * The SPNEGO tokens or Kerberos/NTLM messages exchanged between the
		 * client and the server are encapsulated in the negoTokens field of the
		 * TSRequest structure (section 2.2.1). Both the client and the server
		 * use
		 */
		NTLMState ntlm = new NTLMState(state);
		byte[] clientNonce = new byte[32];
		new SecureRandom().nextBytes(clientNonce);
		
		
		
		byte[] negotiateData = new NTLMNegotiate(ntlm).write().getBytes();
		HexDump.encode(negotiateData, "NEG DATA");
		boolean requestedSpnego = state.getOptions().getCredSspTokenMode()
				== CredSspTokenMode.SPNEGO_NTLM;
		byte[] negotiateToken = requestedSpnego
				? SpnegoToken.initial(negotiateData)
				: negotiateData;
		TSRequest req = new TSRequest(negotiateToken);
		req.setVersion(CREDSSP_VERSION);
		req.setClientNonce(clientNonce);
		BerType send = req.write();
		BerByteArrayOutputStream bos = new BerByteArrayOutputStream();
		send.encode(bos, true);
		
		// MS-NLMP - 2.2.1.1 NEGOTIATE_MESSAGE		
		logger.info("Sending NTLM Negotiate, tokenFormat="
				+ (requestedSpnego ? "SPNEGO/NTLM" : "RAW_NTLM"));
		ntlm.dumpFlags();
		transport.sendPacket(new Packet(bos.getArray()));
		
		// MS-NLMP - 2.2.1.2 CHALLENGE_MESSAGE
		TSRequest challengeRequest = new TSRequest(null);
		challengeRequest.read(new BerInputStream(transport.getIn()).next());
		int peerVersion = Math.min(CREDSSP_VERSION, challengeRequest.getVersion());
		logger.info("CredSSP server version=" + challengeRequest.getVersion()
				+ ", effectiveVersion=" + peerVersion);
		NTLMResponse response = new NTLMResponse(ntlm);
		byte[] challengeTokenData = challengeRequest.getNegoData();
		if (challengeTokenData == null || challengeTokenData.length == 0) {
			throw new IOException("CredSSP server did not return an NTLM challenge.");
		}
		SpnegoToken.Parsed challengeToken = SpnegoToken.parse(challengeTokenData);
		boolean useSpnego = challengeToken.isWrapped();
		byte[] responseData = challengeToken.getResponseToken();
		if (responseData == null || responseData.length == 0) {
			throw new IOException("SPNEGO response did not contain an NTLM challenge.");
		}
		if (requestedSpnego && !useSpnego) {
			logger.info("CredSSP server returned raw NTLM; continuing in RAW_NTLM mode");
		} else if (!requestedSpnego && useSpnego) {
			logger.info("CredSSP server selected SPNEGO/NTLM; continuing in SPNEGO mode");
		}
		logger.info("Received NTLM Challenge");
		HexDump.encode(responseData, "NTLM Challenge");
		response.read(new NTLMPacket(responseData).setPosition(0));
		
		/*
		 * Build the authentication response but don't set it yet as we may need
		 * to create a signature and then set the MIC
		 * 
		 * MS-NLMP - 2.2.1.3 AUTHENTICATE_MESSAGE
		 */
		// MsvAvFlags/MIC_PRESENT必须在计算NTLMv2 blob之前写入TargetInfo；
		// 原实现在Authenticate生成后才修改，导致MIC与NTProof的TargetInfo不一致。
		if (ntlm.getAvPairs() == null) {
			throw new IOException("NTLM challenge did not contain TargetInfo.");
		}
		boolean micRequired = ntlm.getAvPairs().hasTimestamp();
		if (micRequired) {
			ntlm.getAvPairs().setFlags(ntlm.getAvPairs().getFlags() | 0x02);
		}
		// Windows/FreeRDP的NTLMv2客户端会在Authenticate TargetInfo中加入
		// TERMSRV SPN和空的通道绑定哈希；这两个字段必须在NTProof之前写入。
		if (ntlm.getAvPairs().getTargetName() == null
				|| ntlm.getAvPairs().getTargetName().isBlank()) {
			ntlm.getAvPairs().setTargetName("TERMSRV/" + transport.getIo().getAddress());
		}
		if (ntlm.getAvPairs().getChannelHash() == null) {
			ntlm.getAvPairs().setChannelHash(new byte[16]);
		}
		NTLMAuthenticate auth = new NTLMAuthenticate(ntlm);
		byte[] authData = auth.write().getBytes();
		logger.info(String.format("NTLM Authenticate flags=0x%08x, mic=%b, tokenLength=%d",
				ntlm.getFlags(), micRequired, authData.length));
		

		HexDump.encode(authData, "AUTH DATA");
		
		/* Configure targetInfo block for response */
		if (micRequired) {
			/* MIC */
			try {
				Mac mac = Mac.getInstance("HmacMD5");
				mac.init(new SecretKeySpec(ntlm.getExportedSessionKey(), "HmacMD5"));
				mac.update(negotiateData);
				mac.update(responseData);
				mac.update(authData);
				byte[] mic = mac.doFinal();
				auth.setMIC(mic);
				/* Replace existing authData with a new one that has a MIC */
				authData = auth.write().getBytes();
			} catch (NoSuchAlgorithmException nsae) {
				throw new RdesktopCryptoException("Failed to create MIC.", nsae);
			} catch (InvalidKeyException e) {
				throw new RdesktopCryptoException("Failed to create MIC.", e);
			}
		}
		else {
			logger.info("NTLM Challenge未要求MIC");
		}
		/* PubKeyAuth是CredSSP通道绑定的必需字段，与服务器是否要求MIC无关。 */
		byte[] publicKey = transport.getIo().getPublicKey();
		if (publicKey == null || publicKey.length == 0) {
			throw new RdesktopCryptoException(
					"CredSSP requires the TLS server public key, but the TLS transport did not provide it.");
		}
		// NTLM message signature的HMAC使用SigningKey；消息体加密才使用
		// 传入Cipher所持有的SealingKey。原实现把SealKey也作为HMAC key，
		// 服务器因此无法验证PubKeyAuth签名。
		byte[] clientBinding = peerVersion >= 5
				? bindingHash(CLIENT_TO_SERVER_MAGIC, clientNonce, publicKey)
				: publicKey;
		byte[] authenticateToken = authData;
		if (useSpnego) {
			// RFC 4178 protects the exact DER-encoded MechTypeList. Signing it
			// consumes NTLM send sequence 0, so PubKeyAuth correctly uses 1.
			byte[] mechListMic = ntlm.signMessage(ntlm.getClientSignKey(),
					SpnegoToken.mechTypes(), ntlm.getClientSeal());
			authenticateToken = SpnegoToken.response(authData, mechListMic);
			logger.info("Sending NTLM Authenticate with SPNEGO mechListMIC");
		}
		TSRequest authenticateRequest = new TSRequest(authenticateToken);
		authenticateRequest.setVersion(CREDSSP_VERSION);
		authenticateRequest.setClientNonce(clientNonce);
		authenticateRequest.setPubKeyAuth(ntlm.encryptMessage(
				ntlm.getClientSignKey(), clientBinding, ntlm.getClientSeal()));
		send = authenticateRequest.write();
		bos = new BerByteArrayOutputStream();
		send.encode(bos, true);
		logger.info("Sending NTLM Authenticate");
		byte[] authPacketData = bos.getArray();
		HexDump.encode(authPacketData, "AUTH PACKET DATA");
		transport.sendPacket(new Packet(authPacketData));
		logger.info("Receiving NTLM Authenticate Response");
		// 服务器在此返回一个有明确BER长度的TSRequest。不能读到EOF：
		// TLS连接会继续承载RDP会话，读到EOF会永久阻塞。
		TSRequest publicKeyResponse = new TSRequest(null);
		publicKeyResponse.read(new BerInputStream(transport.getIn()).next());
		if (useSpnego && publicKeyResponse.getNegoData() != null) {
			SpnegoToken.Parsed finalToken = SpnegoToken.parse(publicKeyResponse.getNegoData());
			if (!finalToken.isWrapped()) {
				throw new IOException("CredSSP server returned an invalid final SPNEGO token.");
			}
			byte[] serverMechListMic = finalToken.getMechListMic();
			if (serverMechListMic != null) {
				// Verify before PubKeyAuth: the MIC occupies server receive
				// sequence 0 and advances the shared server-to-client RC4 stream.
				ntlm.verifySignature(ntlm.getServerSignKey(), SpnegoToken.mechTypes(),
						serverMechListMic, ntlm.getServerSeal());
				logger.info("Verified SPNEGO server mechListMIC");
			}
		}
		byte[] sealedServerBinding = publicKeyResponse.getPubKeyAuth();
		if (sealedServerBinding == null || sealedServerBinding.length == 0) {
			throw new IOException("CredSSP server did not return pubKeyAuth.");
		}
		byte[] serverBinding = ntlm.decryptMessage(ntlm.getServerSignKey(),
				sealedServerBinding, ntlm.getServerSeal());
		byte[] expectedServerBinding;
		if (peerVersion >= 5) {
			expectedServerBinding = bindingHash(SERVER_TO_CLIENT_MAGIC, clientNonce, publicKey);
		} else {
			expectedServerBinding = publicKey.clone();
			incrementLittleEndian(expectedServerBinding);
		}
		if (!MessageDigest.isEqual(expectedServerBinding, serverBinding)) {
			throw new RdesktopCryptoException("CredSSP server public-key binding verification failed.");
		}
		logger.info("CredSSP server public-key binding verified");

		// SPNEGO已建立密封上下文。发送用户凭据，它必须作为编码后的
		// TSCredentials整体进行NTLM seal/sign，再放入TSRequest.authInfo。
		java.util.List<String> creds = state.getCredentialProvider().getCredentials("nla", 0,
				com.tangluobo.tomato.rdp.CredentialProvider.CredentialType.DOMAIN,
				com.tangluobo.tomato.rdp.CredentialProvider.CredentialType.USERNAME,
				com.tangluobo.tomato.rdp.CredentialProvider.CredentialType.PASSWORD);
		TSPasswordCreds passwordCreds = new TSPasswordCreds();
		passwordCreds.domainName = creds.get(0);
		passwordCreds.userName = creds.get(1);
		passwordCreds.password = creds.get(2) == null ? new char[0] : creds.get(2).toCharArray();
		String delegatedAccount = passwordCreds.domainName == null || passwordCreds.domainName.isBlank()
				? passwordCreds.userName
				: passwordCreds.domainName + "\\" + passwordCreds.userName;
		logger.info("Preparing CredSSP delegated credentials: account="
				+ (delegatedAccount == null || delegatedAccount.isBlank() ? "(empty)" : delegatedAccount)
				+ ", passwordPresent=" + (passwordCreds.password.length > 0));
		TSCredentials credentials = new TSCredentials();
		credentials.credType = 1;
		credentials.credentials = passwordCreds;
		BerByteArrayOutputStream credentialBytes = new BerByteArrayOutputStream();
		credentials.write().encode(credentialBytes, true);

		TSRequest credentialRequest = new TSRequest(null);
		credentialRequest.setVersion(CREDSSP_VERSION);
		credentialRequest.setClientNonce(clientNonce);
		credentialRequest.setAuthInfo(ntlm.encryptMessage(ntlm.getClientSignKey(),
				credentialBytes.getArray(), ntlm.getClientSeal()));
		send = credentialRequest.write();
		bos = new BerByteArrayOutputStream();
		send.encode(bos, true);
		logger.info("Sending CredSSP delegated credentials");
		transport.sendPacket(new Packet(bos.getArray()));
	}

	private byte[] bindingHash(byte[] magic, byte[] nonce, byte[] publicKey)
			throws RdesktopCryptoException {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			sha256.update(magic);
			sha256.update(nonce);
			sha256.update(publicKey);
			return sha256.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new RdesktopCryptoException("SHA-256 is not available for CredSSP binding.", e);
		}
	}

	private void incrementLittleEndian(byte[] value) {
		for (int i = 0; i < value.length; i++) {
			value[i]++;
			if (value[i] != 0) {
				return;
			}
		}
	}
}
