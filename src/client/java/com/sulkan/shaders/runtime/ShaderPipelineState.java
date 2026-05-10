package com.sulkan.shaders.runtime;

import com.sulkan.shaders.config.ShaderConfig;

public record ShaderPipelineState(
		boolean shadersEnabled,
		boolean shadowsEnabled,
		boolean aoEnabled,
		boolean waterEnabled,
		boolean cloudsEnabled,
		boolean atmosphereEnabled,
		boolean bloomEnabled,
		boolean fxaaEnabled) {
	public static ShaderPipelineState fromConfig(ShaderConfig config) {
		boolean enabled = config.enabled();
		return new ShaderPipelineState(
				enabled,
				enabled && config.shadowQualityPreset().enabled(),
				enabled && config.aoQualityPreset().enabled(),
				enabled && config.waterQualityPreset().enabled(),
				enabled && config.cloudQualityPreset().enabled(),
				enabled && config.atmosphereQualityPreset().enabled(),
				enabled && config.bloomEnabled(),
				enabled && config.antiAliasingMode().enabled());
	}

	public boolean hasAnyEffect() {
		return this.shadowsEnabled
				|| this.aoEnabled
				|| this.waterEnabled
				|| this.cloudsEnabled
				|| this.atmosphereEnabled
				|| this.bloomEnabled
				|| this.fxaaEnabled;
	}
}
