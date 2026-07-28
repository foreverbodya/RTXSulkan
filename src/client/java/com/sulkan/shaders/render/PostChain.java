package com.sulkan.shaders.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;

public final class PostChain {
	private static final PostChain INSTANCE = new PostChain();

	private RenderTarget targetA;
	private RenderTarget targetB;
	private int width = -1;
	private int height = -1;

	private PostChain() {
	}

	public static PostChain get() {
		return INSTANCE;
	}

	public RenderTarget begin(CommandEncoder encoder, RenderTarget mainTarget) {
		int targetWidth = Math.max(1, mainTarget.width);
		int targetHeight = Math.max(1, mainTarget.height);
		this.ensureTargets(targetWidth, targetHeight);
		this.copy(encoder, mainTarget, this.targetA, targetWidth, targetHeight);
		return this.targetA;
	}

	public RenderTarget output(RenderTarget currentSource, RenderTarget mainTarget, int remainingPasses) {
		if (remainingPasses <= 1) {
			return mainTarget;
		}
		return currentSource == this.targetA ? this.targetB : this.targetA;
	}

	public void copyToMain(CommandEncoder encoder, RenderTarget source, RenderTarget mainTarget) {
		if (source == mainTarget) {
			return;
		}
		this.copy(encoder, source, mainTarget, Math.max(1, mainTarget.width), Math.max(1, mainTarget.height));
	}

	public void close() {
		if (this.targetA != null) {
			this.targetA.destroyBuffers();
			this.targetA = null;
		}
		if (this.targetB != null) {
			this.targetB.destroyBuffers();
			this.targetB = null;
		}
		this.width = -1;
		this.height = -1;
	}

	private void ensureTargets(int width, int height) {
		if (this.targetA != null && this.targetB != null && this.width == width && this.height == height) {
			return;
		}
		this.close();
		this.width = width;
		this.height = height;
		this.targetA = ShaderRenderTargets.color(width, height).allocate();
		ShaderRenderTargets.color(width, height).prepare(this.targetA);
		this.targetB = ShaderRenderTargets.color(width, height).allocate();
		ShaderRenderTargets.color(width, height).prepare(this.targetB);
	}

	private void copy(CommandEncoder encoder, RenderTarget source, RenderTarget target, int width, int height) {
		encoder.copyTextureToTexture(source.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, width, height);
	}
}
