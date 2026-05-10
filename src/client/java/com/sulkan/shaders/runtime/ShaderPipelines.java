package com.sulkan.shaders.runtime;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;

public final class ShaderPipelines {
	public static RenderPipeline PASSTHROUGH;
	public static RenderPipeline FXAA;
	public static RenderPipeline AO_APPLY;
	public static RenderPipeline BLOOM_PREFILTER;
	public static RenderPipeline WATER_COMPOSITE;
	public static RenderPipeline CLOUDS_COMPOSITE;
	public static RenderPipeline VOLUMETRIC_CLOUDS;
	public static RenderPipeline VOLUMETRIC_CLOUDS_COMPOSITE;
	public static RenderPipeline ATMOSPHERE_COMPOSITE;

	private ShaderPipelines() {
	}

	public static void ensureInitialized(ShaderPipelineState state) {
		if (!state.hasAnyEffect()) {
			close();
			return;
		}

		PASSTHROUGH = pipeline("pipeline/passthrough", "passthrough", "SceneColorSampler");
		FXAA = state.fxaaEnabled() ? pipeline("pipeline/fxaa", "fxaa", "SceneColorSampler") : null;
		AO_APPLY = state.aoEnabled() ? pipeline("pipeline/ao", "ao_apply", "SceneColorSampler") : null;
		BLOOM_PREFILTER = state.bloomEnabled() ? pipeline("pipeline/bloom_prefilter", "bloom_prefilter", "SceneColorSampler") : null;
		WATER_COMPOSITE = state.waterEnabled() ? waterPipeline(state.shadowsEnabled()) : null;
		CLOUDS_COMPOSITE = null;
		VOLUMETRIC_CLOUDS = state.cloudsEnabled() ? volumetricCloudTracePipeline() : null;
		VOLUMETRIC_CLOUDS_COMPOSITE = state.cloudsEnabled() ? volumetricCloudCompositePipeline() : null;
		ATMOSPHERE_COMPOSITE = state.atmosphereEnabled() ? atmospherePipeline() : null;
	}

	public static void close() {
		PASSTHROUGH = null;
		FXAA = null;
		AO_APPLY = null;
		BLOOM_PREFILTER = null;
		WATER_COMPOSITE = null;
		CLOUDS_COMPOSITE = null;
		VOLUMETRIC_CLOUDS = null;
		VOLUMETRIC_CLOUDS_COMPOSITE = null;
		ATMOSPHERE_COMPOSITE = null;
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
						.withUniform("CloudParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}

	private static RenderPipeline waterPipeline(boolean shadowsEnabled) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(ShaderResourceIds.id("pipeline/water"))
				.withVertexShader(ShaderResourceIds.coreShader("fullscreen"))
				.withFragmentShader(ShaderResourceIds.coreShader("water_composite"));
		BindGroupLayout.Builder layout = BindGroupLayout.builder()
				.withSampler("SceneColorSampler")
				.withSampler("SceneDepthSampler")
				.withSampler("WaterSceneColorSampler")
				.withSampler("WaterSceneDepthSampler")
				.withUniform("SulkanSceneParams", UniformType.UNIFORM_BUFFER);
		if (shadowsEnabled) {
			builder.withShaderDefine("SULKAN_WATER_SHADOWS");
			layout.withSampler("SulkanShadowMap0")
					.withSampler("SulkanShadowMap1")
					.withSampler("SulkanShadowMap2")
					.withSampler("SulkanShadowMap3")
					.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER);
		}
		return builder.withBindGroupLayout(layout.build()).build();
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
						.withUniform("AtmosphereParams", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
	}
}
