package com.tangluobo.tomato.rdp;

/**
 * Wire format used for the NTLM authentication tokens carried by CredSSP.
 *
 * <p>{@link #RAW_NTLM} preserves the format used by older versions of this
 * client. {@link #SPNEGO_NTLM} wraps the same NTLM messages in RFC 4178
 * NegTokenInit/NegTokenResp structures.</p>
 */
public enum CredSspTokenMode {
	RAW_NTLM,
	SPNEGO_NTLM
}
