package com.sulkan.shaders.effect;

import com.sulkan.shaders.runtime.ShaderPipelineState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ShaderEffectRegistry {
	private static final Map<String, ShaderEffect> EFFECTS = new LinkedHashMap<>();

	static {
		register(new BasicShaderEffect("shadows"));
		register(new BasicShaderEffect("ao"));
		register(new BasicShaderEffect("water"));
		register(new BasicShaderEffect("clouds"));
		register(new BasicShaderEffect("atmosphere"));
		register(new BasicShaderEffect("bloom"));
		register(new BasicShaderEffect("fxaa"));
	}

	private ShaderEffectRegistry() {
	}

	public static Collection<ShaderEffect> effects() {
		return EFFECTS.values();
	}

	public static void applyState(ShaderPipelineState state) {
		set("shadows", state.shadowsEnabled());
		set("ao", state.aoEnabled());
		set("water", state.waterEnabled());
		set("clouds", state.cloudsEnabled());
		set("atmosphere", state.atmosphereEnabled());
		set("bloom", state.bloomEnabled());
		set("fxaa", state.fxaaEnabled());
	}

	public static void reloadResources() {
		for (ShaderEffect effect : EFFECTS.values()) {
			if (effect.enabled()) {
				effect.reloadResources();
			}
		}
	}

	public static void close() {
		for (ShaderEffect effect : EFFECTS.values()) {
			effect.close();
		}
	}

	private static void register(ShaderEffect effect) {
		EFFECTS.put(effect.id(), effect);
	}

	private static void set(String id, boolean enabled) {
		ShaderEffect effect = EFFECTS.get(id);
		if (effect != null) {
			effect.setEnabled(enabled);
		}
	}
}
