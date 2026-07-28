package com.sulkan.shaders.render.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sulkan.shaders.render.ShaderRenderTargets;

public final class WaterSceneCapture {
	private static RenderTarget capturedSceneTarget;
	private static int capturedWidth = -1;
	private static int capturedHeight = -1;

	private WaterSceneCapture() {
	}

	public static void captureFrom(RenderTarget source) {
		if (source == null || source.getColorTexture() == null || source.getDepthTexture() == null) {
			return;
		}

		ensureTarget(source.width, source.height);
		if (capturedSceneTarget == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(source.getColorTexture(), capturedSceneTarget.getColorTexture(), 0, 0, 0, 0, 0, source.width, source.height);
		encoder.copyTextureToTexture(source.getDepthTexture(), capturedSceneTarget.getDepthTexture(), 0, 0, 0, 0, 0, source.width, source.height);
		// Command encoders share the device backend. The post chain submits once
		// after translucent terrain, keeping this copy ordered without stalling Vulkan.
	}

	public static GpuTextureView colorTextureView() {
		return capturedSceneTarget == null ? null : capturedSceneTarget.getColorTextureView();
	}

	public static GpuTextureView depthTextureView() {
		return capturedSceneTarget == null ? null : capturedSceneTarget.getDepthTextureView();
	}

	public static boolean hasCapture() {
		return capturedSceneTarget != null
				&& capturedSceneTarget.getColorTextureView() != null
				&& capturedSceneTarget.getDepthTextureView() != null;
	}

	private static void ensureTarget(int width, int height) {
		if (capturedSceneTarget != null && capturedWidth == width && capturedHeight == height) {
			return;
		}

		destroyTarget();
		var descriptor = ShaderRenderTargets.color(width, height);
		capturedSceneTarget = descriptor.allocate();
		descriptor.prepare(capturedSceneTarget);
		capturedWidth = width;
		capturedHeight = height;
	}

	private static void destroyTarget() {
		if (capturedSceneTarget != null) {
			capturedSceneTarget.destroyBuffers();
			capturedSceneTarget = null;
		}
		capturedWidth = -1;
		capturedHeight = -1;
	}

	public static void close() {
		destroyTarget();
	}
}
