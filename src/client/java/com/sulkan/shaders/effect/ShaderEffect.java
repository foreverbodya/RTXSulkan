package com.sulkan.shaders.effect;

public interface ShaderEffect {
	String id();

	boolean enabled();

	void setEnabled(boolean enabled);

	default void reloadResources() {
	}

	default void close() {
	}
}
