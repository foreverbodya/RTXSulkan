package com.sulkan.shaders.runtime;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;

public final class ShaderPipelines {
	public static RenderPipeline PASSTHROUGH;
	public static RenderPipeline FXAA;
	public static RenderPipeline AO_APPLY;
	public static RenderPipeline AO_COMPOSITE;
	public static RenderPipeline BLOOM_PREFILTER;
	public static RenderPipeline WATER_COMPOSITE;
	public static RenderPipeline WATER_TRACE;
	public static RenderPipeline CLOUDS_COMPOSITE;
	public static RenderPipeline VOLUMETRIC_CLOUDS;
	public static RenderPipeline VOLUMETRIC_CLOUDS_COMPOSITE;
	public static RenderPipeline ATMOSPHERE_COMPOSITE;
	public static RenderPipeline ATMOSPHERE_SHAFTS;

	private ShaderPipelines() {
	}

	public static void ensureInitialized(ShaderPipelineState state) {
		if (!state.hasAnyEffect()) {
			close();
			return;
		}

		PASSTHROUGH = pipeline("pipeline/passthrough", "passthrough", "SceneColorSampler");
		FXAA = state.fxaaEnabled() ? pipeline("pipeline/fxaa", "fxaa", "SceneColorSampler") : null;
		AO_APPLY = state.aoEnabled() ? aoTracePipeline() : null;
		AO_COMPOSITE = state.aoEnabled() ? aoCompositePipeline() : null;
		BLOOM_PREFILTER = state.bloomEnabled() ? pipeline("pipeline/bloom_prefilter", "bloom_prefilter", "SceneColorSampler") : null;
		WATER_TRACE = state.waterEnabled() ? waterTracePipeline() : null;
		WATER_COMPOSITE = state.waterEnabled() ? waterCompositePipeline() : null;
		CLOUDS_COMPOSITE = null;
		VOLUMETRIC_CLOUDS = state.cloudsEnabled() ? volumetricCloudTracePipeline() : null;
		VOLUMETRIC_CLOUDS_COMPOSITE = state.cloudsEnabled() ? volumetricCloudCompositePipeline() : null;
		ATMOSPHERE_SHAFTS = state.atmosphereEnabled() ? atmosphereShaftPipeline() : null;
		ATMOSPHERE_COMPOSITE = state.atmosphereEnabled() ? atmospherePipeline() : null;
	}

	public static void close() {
		PASSTHROUGH = null;
		FXAA = null;
		AO_APPLY = null;
		AO_COMPOSITE = null;
		BLOOM_PREFILTER = null;
		WATER_COMPOSITE = null;
		WATER_TRACE = null;
		CLOUDS_COMPOSITE = null;
		VOLUMETRIC_CLOUDS = null;
		VOLUMETRIC_CLOUDS_COMPOSITE = null;
		ATMOSPHERE_COMPOSITE = null;
		ATMOSPHERE_SHAFTS = null;
	}

	private static RenderPipeline pipeline(String location, String fragmentShader, String samplerName) {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id(location))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader(fragmentShader))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(samplerName)
						.withSampler("SceneDepthSampler")
						.withUniform("SulkanSceneParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline volumetricCloudTracePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/volumetric_clouds_trace"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("volumetric_clouds"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("CloudNoiseSampler")
						.withSampler("SceneDepthSampler")
						.withUniform("CloudParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline waterTracePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/water_trace"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("water_trace"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneColorSampler")
						.withSampler("SceneDepthSampler")
						.withSampler("WaterSceneColorSampler")
						.withSampler("WaterSceneDepthSampler")
						.withUniform("SulkanSceneParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline waterCompositePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/water_composite"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("water_composite"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneColorSampler")
						.withSampler("SceneDepthSampler")
						.withSampler("WaterSceneColorSampler")
						.withSampler("WaterSceneDepthSampler")
						.withSampler("WaterTraceSampler")
						.withUniform("SulkanSceneParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline aoTracePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/ao_trace"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("ao_apply"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneDepthSampler")
						.withUniform("SulkanSceneParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline aoCompositePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/ao_composite"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("ao_composite"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneColorSampler")
						.withSampler("SceneDepthSampler")
						.withSampler("AoSampler")
						.build())
				.build();
	}

	private static RenderPipeline volumetricCloudCompositePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/volumetric_clouds_composite"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("volumetric_clouds_composite"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneColorSampler")
						.withSampler("SceneDepthSampler")
						.withSampler("CloudTraceSampler")
						.build())
				.build();
	}

	private static RenderPipeline atmospherePipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/atmosphere_composite"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("atmosphere_composite"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneColorSampler")
						.withSampler("SceneDepthSampler")
						.withSampler("AtmosphereShaftSampler")
						.withUniform("AtmosphereParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline atmosphereShaftPipeline() {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/atmosphere_shafts"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("atmosphere_shafts"))
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler("SceneDepthSampler")
						.withUniform("AtmosphereParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}
}
