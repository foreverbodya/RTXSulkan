package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sulkan.shaders.render.FullscreenPostRenderer;
import com.sulkan.shaders.render.PostChain;
import com.sulkan.shaders.render.ScenePostUniforms;
import com.sulkan.shaders.render.atmosphere.AtmosphereRenderer;
import com.sulkan.shaders.render.cloud.VolumetricCloudRenderer;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererPostMixin {
	@Shadow
	@Final
	private GameRenderer gameRenderer;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Unique
	private final Matrix4f sulkan$cloudBobModelView = new Matrix4f();

	@Inject(method = "render", at = @At("HEAD"))
	private void sulkan$prepareVolumetricCloudFrame(
			GraphicsResourceAllocator resourceAllocator,
			DeltaTracker deltaTracker,
			boolean renderOutline,
			CameraRenderState cameraState,
			Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean shouldRenderSky,
			CallbackInfo ci) {
		if (ShaderRuntime.resourceReloading()) {
			return;
		}
		if (ShaderRuntime.pipelineState().cloudsEnabled()) {
			VolumetricCloudRenderer.get().beginFrame(
					cameraState,
					this.levelRenderState,
					cameraState == null ? null : cameraState.projectionMatrix,
					this.sulkan$getCloudModelViewMatrix(cameraState, modelViewMatrix),
					fogColor,
					this.gameRenderer.mainRenderTarget());
		}
		if (ShaderRuntime.pipelineState().atmosphereEnabled()) {
			AtmosphereRenderer.get().beginFrame(
					cameraState,
					this.levelRenderState,
					modelViewMatrix,
					fogColor,
					this.gameRenderer.mainRenderTarget());
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void sulkan$runPostEffects(
			GraphicsResourceAllocator resourceAllocator,
			DeltaTracker deltaTracker,
			boolean renderOutline,
			CameraRenderState cameraState,
			Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean shouldRenderSky,
			CallbackInfo ci) {
		if (ShaderRuntime.resourceReloading()) {
			return;
		}
		var state = ShaderRuntime.pipelineState();
		int passCount = 0;
		if (state.aoEnabled()) {
			passCount++;
		}
		if (state.waterEnabled()) {
			passCount++;
		}
		VolumetricCloudRenderer cloudRenderer = VolumetricCloudRenderer.get();
		boolean renderVolumetricClouds = state.cloudsEnabled() && cloudRenderer.isRequestedThisFrame();
		if (renderVolumetricClouds) {
			passCount++;
		}
		boolean renderAtmosphere = state.atmosphereEnabled();
		if (renderAtmosphere) {
			passCount++;
		}
		if (state.bloomEnabled()) {
			passCount++;
		}
		if (state.fxaaEnabled()) {
			passCount++;
		}
		if (passCount == 0) {
			return;
		}

		RenderTarget mainTarget = this.gameRenderer.mainRenderTarget();
		if (mainTarget == null || mainTarget.getColorTexture() == null) {
			return;
		}

		ScenePostUniforms.prepare(cameraState, this.levelRenderState, modelViewMatrix, fogColor, mainTarget, deltaTracker);
		PostChain postChain = PostChain.get();
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		try {
			RenderTarget source = postChain.begin(encoder, mainTarget);
			int remainingPasses = passCount;

			if (state.aoEnabled()) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (FullscreenPostRenderer.applyAmbientOcclusion(encoder, source, output)) {
					source = output;
				}
				remainingPasses--;
			}
			if (state.waterEnabled()) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (FullscreenPostRenderer.applyWaterComposite(encoder, source, output)) {
					source = output;
				}
				remainingPasses--;
			}
			if (renderVolumetricClouds) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (cloudRenderer.execute(encoder, source, output, source)) {
					source = output;
				}
				remainingPasses--;
			}
			if (renderAtmosphere) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (AtmosphereRenderer.get().execute(encoder, source, output, source)) {
					source = output;
				}
				remainingPasses--;
			}
			if (state.bloomEnabled()) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (FullscreenPostRenderer.applyBloom(encoder, source, output)) {
					source = output;
				}
				remainingPasses--;
			}
			if (state.fxaaEnabled()) {
				RenderTarget output = postChain.output(source, mainTarget, remainingPasses);
				if (FullscreenPostRenderer.applyFxaa(encoder, source, output)) {
					source = output;
				}
			}

			if (source != mainTarget) {
				postChain.copyToMain(encoder, source, mainTarget);
			}
		} finally {
			encoder.submit();
		}
	}

	@Unique
	private Matrix4fc sulkan$getCloudModelViewMatrix(CameraRenderState cameraState, Matrix4fc modelViewMatrix) {
		Matrix4fc baseModelViewMatrix = modelViewMatrix;
		if (baseModelViewMatrix == null && cameraState != null) {
			baseModelViewMatrix = cameraState.viewRotationMatrix;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.options.bobView().get() || cameraState == null) {
			return baseModelViewMatrix;
		}

		CameraEntityRenderState entityState = cameraState.entityRenderState;
		if (entityState == null || !entityState.isPlayer) {
			return baseModelViewMatrix;
		}

		float walkAngle = entityState.backwardsInterpolatedWalkDistance * Mth.PI;
		float bob = entityState.bob;
		float zRadians = Mth.sin(walkAngle) * bob * 3.0f * Mth.DEG_TO_RAD;
		float xRadians = Math.abs(Mth.cos(walkAngle - 0.2f) * bob) * 5.0f * Mth.DEG_TO_RAD;
		Matrix4f bobRotationMatrix = this.sulkan$cloudBobModelView.identity().rotateZ(zRadians).rotateX(xRadians);
		if (baseModelViewMatrix != null) {
			bobRotationMatrix.mul(baseModelViewMatrix);
		}
		return bobRotationMatrix;
	}
}
