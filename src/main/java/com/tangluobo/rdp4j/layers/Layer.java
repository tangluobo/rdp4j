package com.tangluobo.rdp4j.layers;

public interface Layer<P extends Layer<?>> {
	P getParent();
}
