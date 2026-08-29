package com.tangluobo.tomato.rdp;

import java.util.List;

public interface CredentialProvider {
	public enum CredentialType {
		DOMAIN, USERNAME, PASSWORD
	}

	List<String> getCredentials(String scope, int attempts, CredentialType... types);
}
