package com.sulkan.shaders.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class ShaderRenderTargets {
	private static final Vector4fc CLEAR = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

	private ShaderRenderTargets() {
	}

	public static RenderTargetDescriptor color(int width, int height) {
		return colorDepth(width, height);
	}

	public static RenderTargetDescriptor colorOnly(int width, int height) {
		return new RenderTargetDescriptor(width, height, false, CLEAR, GpuFormat.RGBA8_UNORM);
	}

	public static RenderTargetDescriptor colorDepth(int width, int height) {
		return new RenderTargetDescriptor(width, height, true, CLEAR, GpuFormat.RGBA8_UNORM);
	}
}
