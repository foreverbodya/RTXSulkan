package com.sulkan.shaders.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.water.WaterSceneCapture;
import com.sulkan.shaders.runtime.ShaderPipelines;
import com.sulkan.shaders.runtime.ShaderRuntime;

import java.util.Optional;
import java.util.OptionalDouble;

public final class FullscreenPostRenderer {
	private static GpuSampler linearSampler;
	private static GpuSampler nearestSampler;

	private FullscreenPostRenderer() {
	}

	public static boolean applyFxaa(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.FXAA == null) {
			return false;
		}
		return apply(encoder, "Sulkan FXAA", ShaderPipelines.FXAA, source, output);
	}

	public static boolean applyBloom(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.BLOOM_PREFILTER == null) {
			return false;
		}
		return apply(encoder, "Sulkan Bloom", ShaderPipelines.BLOOM_PREFILTER, source, output);
	}

	public static boolean applyAmbientOcclusion(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.AO_APPLY == null) {
			return false;
		}
		return apply(encoder, "Sulkan AO", ShaderPipelines.AO_APPLY, source, output);
	}

	public static boolean applyAtmosphere(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.ATMOSPHERE_COMPOSITE == null) {
			return false;
		}
		return apply(encoder, "Sulkan Atmosphere", ShaderPipelines.ATMOSPHERE_COMPOSITE, source, output);
	}

	public static boolean applyClouds(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.CLOUDS_COMPOSITE == null) {
			return false;
		}
		return apply(encoder, "Sulkan Clouds", ShaderPipelines.CLOUDS_COMPOSITE, source, output);
	}

	public static boolean applyWaterComposite(CommandEncoder encoder, RenderTarget source, RenderTarget output) {
		if (ShaderPipelines.WATER_COMPOSITE == null) {
			return false;
		}
		return apply(encoder, "Sulkan Water Composite", ShaderPipelines.WATER_COMPOSITE, source, output);
	}

	public static void close() {
		if (linearSampler != null) {
			linearSampler.close();
			linearSampler = null;
		}
		if (nearestSampler != null) {
			nearestSampler.close();
			nearestSampler = null;
		}
	}

	private static boolean apply(CommandEncoder encoder, String label, RenderPipeline pipeline, RenderTarget source, RenderTarget output) {
		if (source == null || output == null || source.getColorTextureView() == null || output.getColorTextureView() == null) {
			return false;
		}
		ensureSampler();
		if (source.getDepthTexture() != null && output.getDepthTexture() != null && source != output) {
			encoder.copyTextureToTexture(
					source.getDepthTexture(),
					output.getDepthTexture(),
					0,
					0,
					0,
					0,
					0,
					Math.max(1, output.width),
					Math.max(1, output.height));
		}
		GpuBufferSlice sceneParams = ScenePostUniforms.upload(encoder);
		try (RenderPass pass = encoder.createRenderPass(
				() -> label,
				output.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", source.getColorTextureView(), sceneColorSampler(pipeline));
			if (source.getDepthTextureView() != null) {
				pass.bindTexture("SceneDepthSampler", source.getDepthTextureView(), nearestSampler);
			} else {
				pass.bindTexture("SceneDepthSampler", source.getColorTextureView(), linearSampler);
			}
			if (pipeline == ShaderPipelines.WATER_COMPOSITE) {
				if (WaterSceneCapture.hasCapture()) {
					pass.bindTexture("WaterSceneColorSampler", WaterSceneCapture.colorTextureView(), linearSampler);
					pass.bindTexture("WaterSceneDepthSampler", WaterSceneCapture.depthTextureView(), nearestSampler);
				} else {
					pass.bindTexture("WaterSceneColorSampler", source.getColorTextureView(), linearSampler);
					pass.bindTexture("WaterSceneDepthSampler", source.getDepthTextureView(), nearestSampler);
				}
				if (ShaderRuntime.pipelineState().shadowsEnabled()) {
					bindWaterShadowResources(pass, source);
				}
			}
			pass.setUniform("SulkanSceneParams", sceneParams);
			pass.draw(3, 1, 0, 0);
		}
		return true;
	}

	private static void ensureSampler() {
		if (linearSampler == null) {
			linearSampler = RenderSystem.getDevice().createSampler(
					AddressMode.CLAMP_TO_EDGE,
					AddressMode.CLAMP_TO_EDGE,
					FilterMode.LINEAR,
					FilterMode.LINEAR,
					1,
					OptionalDouble.of(0.0));
		}
		if (nearestSampler == null) {
			nearestSampler = RenderSystem.getDevice().createSampler(
					AddressMode.CLAMP_TO_EDGE,
					AddressMode.CLAMP_TO_EDGE,
					FilterMode.NEAREST,
					FilterMode.NEAREST,
					1,
					OptionalDouble.of(0.0));
		}
	}

	private static GpuSampler sceneColorSampler(RenderPipeline pipeline) {
		return pipeline == ShaderPipelines.FXAA || pipeline == ShaderPipelines.BLOOM_PREFILTER ? linearSampler : nearestSampler;
	}

	private static void bindWaterShadowResources(RenderPass pass, RenderTarget fallback) {
		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		if (shadows.shadowDataSlice() != null) {
			pass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
			for (int cascade = 0; cascade < 4; cascade++) {
				RenderTarget target = shadows.target(cascade);
				if (target != null && target.getDepthTextureView() != null) {
					pass.bindTexture("SulkanShadowMap" + cascade, target.getDepthTextureView(), nearestSampler);
				} else {
					pass.bindTexture("SulkanShadowMap" + cascade, fallback.getDepthTextureView(), nearestSampler);
				}
			}
			return;
		}
	}
}
