package com.tangluobo.tomato.rdp.layers;

public interface Layer<P extends Layer<?>> {
	P getParent();
}
