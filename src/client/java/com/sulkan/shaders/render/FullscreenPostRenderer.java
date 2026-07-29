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
import com.sulkan.shaders.render.water.WaterSceneCapture;
import com.sulkan.shaders.runtime.ShaderPipelines;
import com.sulkan.shaders.runtime.ShaderRuntime;

import java.util.Optional;
import java.util.OptionalDouble;

public final class FullscreenPostRenderer {
	private static GpuSampler linearSampler;
	private static GpuSampler nearestSampler;
	private static RenderTarget aoTarget;
	private static RenderTarget waterTraceTarget;
	private static int aoWidth = -1;
	private static int aoHeight = -1;
	private static int waterWidth = -1;
	private static int waterHeight = -1;

	private FullscreenPostRenderer() {
	}

	public static boolean applyFxaa(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.FXAA == null) {
			return false;
		}
		return apply(encoder, "Sulkan FXAA", ShaderPipelines.FXAA, source, output, depthSource);
	}

	public static boolean applyBloom(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.BLOOM_PREFILTER == null) {
			return false;
		}
		return apply(encoder, "Sulkan Bloom", ShaderPipelines.BLOOM_PREFILTER, source, output, depthSource);
	}

	public static boolean applyAmbientOcclusion(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.AO_APPLY == null || ShaderPipelines.AO_COMPOSITE == null
				|| !validTargets(source, output, depthSource)) {
			return false;
		}
		ensureSampler();
		int divisor = switch (ShaderRuntime.config().aoQualityPreset()) {
			case LOW -> 4;
			case MEDIUM, HIGH -> 2;
			case ULTRA, OFF -> 1;
		};
		ensureAoTarget(Math.max(1, depthSource.width / divisor), Math.max(1, depthSource.height / divisor));
		GpuBufferSlice sceneParams = ScenePostUniforms.upload(encoder);
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan AO Trace",
				aoTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.AO_APPLY);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneDepthSampler", depthSource.getDepthTextureView(), nearestSampler);
			pass.setUniform("SulkanSceneParams", sceneParams);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan AO Composite",
				output.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.AO_COMPOSITE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", source.getColorTextureView(), nearestSampler);
			pass.bindTexture("SceneDepthSampler", depthSource.getDepthTextureView(), nearestSampler);
			pass.bindTexture("AoSampler", aoTarget.getColorTextureView(), linearSampler);
			pass.draw(3, 1, 0, 0);
		}
		return true;
	}

	public static boolean applyAtmosphere(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.ATMOSPHERE_COMPOSITE == null) {
			return false;
		}
		return apply(encoder, "Sulkan Atmosphere", ShaderPipelines.ATMOSPHERE_COMPOSITE, source, output, depthSource);
	}

	public static boolean applyClouds(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.CLOUDS_COMPOSITE == null) {
			return false;
		}
		return apply(encoder, "Sulkan Clouds", ShaderPipelines.CLOUDS_COMPOSITE, source, output, depthSource);
	}

	public static boolean applyWaterComposite(CommandEncoder encoder, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (ShaderPipelines.WATER_TRACE == null || ShaderPipelines.WATER_COMPOSITE == null
				|| !WaterSceneCapture.hasCaptureThisFrame() || !validTargets(source, output, depthSource)) {
			return false;
		}
		ensureSampler();
		int divisor = switch (ShaderRuntime.config().waterQualityPreset()) {
			case LOW, MEDIUM -> 4;
			case HIGH -> 2;
			case ULTRA, OFF -> 1;
		};
		ensureWaterTarget(Math.max(1, depthSource.width / divisor), Math.max(1, depthSource.height / divisor));
		GpuBufferSlice sceneParams = ScenePostUniforms.upload(encoder);
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Water Trace",
				waterTraceTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.WATER_TRACE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", source.getColorTextureView(), nearestSampler);
			pass.bindTexture("SceneDepthSampler", depthSource.getDepthTextureView(), nearestSampler);
			pass.bindTexture("WaterSceneColorSampler", WaterSceneCapture.colorTextureView(), linearSampler);
			pass.bindTexture("WaterSceneDepthSampler", WaterSceneCapture.depthTextureView(), nearestSampler);
			pass.setUniform("SulkanSceneParams", sceneParams);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Water Composite",
				output.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.WATER_COMPOSITE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", source.getColorTextureView(), nearestSampler);
			pass.bindTexture("SceneDepthSampler", depthSource.getDepthTextureView(), nearestSampler);
			pass.bindTexture("WaterSceneColorSampler", WaterSceneCapture.colorTextureView(), linearSampler);
			pass.bindTexture("WaterSceneDepthSampler", WaterSceneCapture.depthTextureView(), nearestSampler);
			pass.bindTexture("WaterTraceSampler", waterTraceTarget.getColorTextureView(), linearSampler);
			pass.setUniform("SulkanSceneParams", sceneParams);
			pass.draw(3, 1, 0, 0);
		}
		return true;
	}

	public static void close() {
		destroyReducedTargets();
		if (linearSampler != null) {
			linearSampler.close();
			linearSampler = null;
		}
		if (nearestSampler != null) {
			nearestSampler.close();
			nearestSampler = null;
		}
	}

	public static void releaseAmbientOcclusionTarget() {
		if (aoTarget != null) {
			aoTarget.destroyBuffers();
			aoTarget = null;
		}
		aoWidth = -1;
		aoHeight = -1;
	}

	public static void releaseWaterTarget() {
		if (waterTraceTarget != null) {
			waterTraceTarget.destroyBuffers();
			waterTraceTarget = null;
		}
		waterWidth = -1;
		waterHeight = -1;
	}

	private static boolean validTargets(RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		return source != null && source.getColorTextureView() != null
				&& output != null && output.getColorTextureView() != null
				&& depthSource != null && depthSource.getDepthTextureView() != null;
	}

	private static void ensureAoTarget(int width, int height) {
		if (aoTarget != null && aoWidth == width && aoHeight == height) {
			return;
		}
		if (aoTarget != null) {
			aoTarget.destroyBuffers();
		}
		var descriptor = ShaderRenderTargets.colorOnly(width, height);
		aoTarget = descriptor.allocate();
		descriptor.prepare(aoTarget);
		aoWidth = width;
		aoHeight = height;
	}

	private static void ensureWaterTarget(int width, int height) {
		if (waterTraceTarget != null && waterWidth == width && waterHeight == height) {
			return;
		}
		if (waterTraceTarget != null) {
			waterTraceTarget.destroyBuffers();
		}
		var descriptor = ShaderRenderTargets.colorOnly(width, height);
		waterTraceTarget = descriptor.allocate();
		descriptor.prepare(waterTraceTarget);
		waterWidth = width;
		waterHeight = height;
	}

	private static void destroyReducedTargets() {
		releaseAmbientOcclusionTarget();
		releaseWaterTarget();
	}

	private static boolean apply(CommandEncoder encoder, String label, RenderPipeline pipeline, RenderTarget source, RenderTarget output, RenderTarget depthSource) {
		if (source == null || output == null || source.getColorTextureView() == null || output.getColorTextureView() == null) {
			return false;
		}
		ensureSampler();
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
			if (depthSource != null && depthSource.getDepthTextureView() != null) {
				pass.bindTexture("SceneDepthSampler", depthSource.getDepthTextureView(), nearestSampler);
			} else {
				pass.bindTexture("SceneDepthSampler", source.getColorTextureView(), linearSampler);
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

}
