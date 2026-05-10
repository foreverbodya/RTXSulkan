package com.sulkan.shaders.effect;

public final class BasicShaderEffect implements ShaderEffect {
	private final String id;
	private boolean enabled;

	public BasicShaderEffect(String id) {
		this.id = id;
	}

	@Override
	public String id() {
		return this.id;
	}

	@Override
	public boolean enabled() {
		return this.enabled;
	}

	@Override
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
