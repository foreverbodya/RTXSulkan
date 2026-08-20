package com.sulkan.shaders.render.atmosphere;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.sulkan.shaders.config.ShaderQualityPreset;
import com.sulkan.shaders.render.ShaderRenderTargets;
import com.sulkan.shaders.render.sky.CustomCelestials;
import com.sulkan.shaders.runtime.ShaderPipelines;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

public final class AtmosphereRenderer {
	private static final int PARAM_BYTES = 64 + 64 + 16 * 6;
	private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

	private static AtmosphereRenderer instance;

	private final ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(PARAM_BYTES).order(ByteOrder.nativeOrder());
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f inverseProjection = new Matrix4f();
	private final Matrix4f modelView = new Matrix4f();
	private final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);
	private final Vector3f sunDirectionView = new Vector3f(0.0f, 1.0f, 0.0f);
	private final Vector3f upDirectionView = new Vector3f(0.0f, 1.0f, 0.0f);
	private final Vector3f skyColor = new Vector3f(0.58f, 0.68f, 0.86f);
	private final Vector3f sunColor = new Vector3f(1.0f, 0.86f, 0.62f);
	private final Vector3f fallbackSkyColor = new Vector3f();

	private GpuBuffer paramsBuffer;
	private GpuBufferSlice paramsSlice;
	private GpuSampler linearSampler;
	private GpuSampler depthSampler;
	private RenderTarget shaftTarget;
	private int shaftWidth = -1;
	private int shaftHeight = -1;
	private boolean preparedThisFrame;
	private int screenWidth = 1;
	private int screenHeight = 1;
	private float sunVisibility = 1.0f;
	private float rainFactor;
	private float renderDistanceStart = 128.0f;
	private float renderDistanceEnd = 192.0f;
	private float skyFogEnd = 192.0f;
	private float environmentalFogEnd = 192.0f;

	private AtmosphereRenderer() {
	}

	public static AtmosphereRenderer get() {
		if (instance == null) {
			instance = new AtmosphereRenderer();
		}
		return instance;
	}

	public static boolean isEnabled() {
		return ShaderRuntime.pipelineState().atmosphereEnabled();
	}

	public void beginFrame(CameraRenderState cameraState, LevelRenderState levelRenderState, Matrix4fc modelViewMatrix, Vector4f fallbackFogColor, RenderTarget mainTarget) {
		if (!isEnabled() || cameraState == null || cameraState.projectionMatrix == null) {
			this.preparedThisFrame = false;
			return;
		}

		this.ensureGpuResources();
		this.projection.set(cameraState.projectionMatrix);
		this.inverseProjection.set(this.projection).invert();
		if (modelViewMatrix != null) {
			this.modelView.set(modelViewMatrix);
		} else if (cameraState.viewRotationMatrix != null) {
			this.modelView.set(cameraState.viewRotationMatrix);
		} else {
			this.modelView.identity();
		}

		if (mainTarget != null) {
			this.screenWidth = Math.max(1, mainTarget.width);
			this.screenHeight = Math.max(1, mainTarget.height);
		}

		this.skyColor.set(0.58f, 0.68f, 0.86f);
		if (levelRenderState != null && levelRenderState.skyRenderState != null) {
			CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.sunAngle, this.sunDirection);
			unpackRgb(levelRenderState.skyRenderState.skyColor, this.skyColor);
		} else {
			this.sunDirection.set(0.0f, 1.0f, 0.0f);
		}

		if (fallbackFogColor != null) {
			this.skyColor.lerp(this.fallbackSkyColor.set(fallbackFogColor.x, fallbackFogColor.y, fallbackFogColor.z), 0.35f);
		}

		this.sunVisibility = clamp01((this.sunDirection.y + 0.08f) / 0.24f);
		float warmth = 1.0f - smoothstep(0.05f, 0.52f, this.sunDirection.y);
		this.sunColor.set(1.0f, 0.86f - 0.18f * warmth, 0.62f - 0.28f * warmth);
		this.modelView.transformDirection(this.sunDirection, this.sunDirectionView).normalize();
		this.modelView.transformDirection(WORLD_UP, this.upDirectionView).normalize();

		float fallbackRenderDistanceEnd = 192.0f;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			fallbackRenderDistanceEnd = Math.max(64.0f, minecraft.options.getEffectiveRenderDistance() * 16.0f);
		}
		this.rainFactor = minecraft != null && minecraft.level != null ? clamp01(minecraft.level.getRainLevel(1.0f)) : 0.0f;
		this.renderDistanceStart = Math.max(0.0f, fallbackRenderDistanceEnd - 96.0f);
		this.renderDistanceEnd = fallbackRenderDistanceEnd;
		this.skyFogEnd = fallbackRenderDistanceEnd;
		this.environmentalFogEnd = fallbackRenderDistanceEnd;
		if (cameraState.fogData != null) {
			float fogRenderDistanceStart = Math.max(0.0f, cameraState.fogData.renderDistanceStart);
			float fogRenderDistanceEnd = Math.max(fogRenderDistanceStart + 1.0f, cameraState.fogData.renderDistanceEnd);
			this.renderDistanceStart = fogRenderDistanceStart;
			this.renderDistanceEnd = fogRenderDistanceEnd;
			this.skyFogEnd = Math.max(1.0f, cameraState.fogData.skyEnd);
			this.environmentalFogEnd = Math.max(1.0f, cameraState.fogData.environmentalEnd);
		}

		this.preparedThisFrame = true;
	}

	public boolean execute(CommandEncoder encoder, RenderTarget sourceColorTarget, RenderTarget outputTarget, RenderTarget depthTarget) {
		if (encoder == null || !this.canExecute(sourceColorTarget, outputTarget, depthTarget)) {
			return false;
		}

		this.ensureGpuResources();
		this.ensureShaftTarget();
		this.upload(encoder);
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Atmosphere Shafts",
				this.shaftTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.ATMOSPHERE_SHAFTS);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneDepthSampler", depthTarget.getDepthTextureView(), this.depthSampler);
			pass.setUniform("AtmosphereParams", this.paramsSlice);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Atmosphere Composite",
				outputTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.ATMOSPHERE_COMPOSITE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", sourceColorTarget.getColorTextureView(), this.depthSampler);
			pass.bindTexture("SceneDepthSampler", depthTarget.getDepthTextureView(), this.depthSampler);
			pass.bindTexture("AtmosphereShaftSampler", this.shaftTarget.getColorTextureView(), this.linearSampler);
			pass.setUniform("AtmosphereParams", this.paramsSlice);
			pass.draw(3, 1, 0, 0);
		}
		return true;
	}

	private boolean canExecute(RenderTarget sourceColorTarget, RenderTarget outputTarget, RenderTarget depthTarget) {
		return this.preparedThisFrame
				&& sourceColorTarget != null
				&& sourceColorTarget.getColorTexture() != null
				&& outputTarget != null
				&& outputTarget.getColorTexture() != null
				&& depthTarget != null
				&& depthTarget.getDepthTexture() != null
				&& ShaderPipelines.ATMOSPHERE_SHAFTS != null
				&& ShaderPipelines.ATMOSPHERE_COMPOSITE != null;
	}

	private void ensureShaftTarget() {
		int divisor = switch (ShaderRuntime.config().atmosphereQualityPreset()) {
			case LOW, MEDIUM -> 4;
			case HIGH, ULTRA, OFF -> 2;
		};
		int width = Math.max(1, this.screenWidth / divisor);
		int height = Math.max(1, this.screenHeight / divisor);
		if (this.shaftTarget != null && this.shaftWidth == width && this.shaftHeight == height) {
			return;
		}
		this.destroyShaftTarget();
		var descriptor = ShaderRenderTargets.colorOnly(width, height);
		this.shaftTarget = descriptor.allocate();
		descriptor.prepare(this.shaftTarget);
		this.shaftWidth = width;
		this.shaftHeight = height;
	}

	private void destroyShaftTarget() {
		if (this.shaftTarget != null) {
			this.shaftTarget.destroyBuffers();
			this.shaftTarget = null;
		}
		this.shaftWidth = -1;
		this.shaftHeight = -1;
	}

	private void upload(CommandEncoder encoder) {
		ShaderQualityPreset quality = ShaderRuntime.config().atmosphereQualityPreset();
		this.uploadBuffer.clear();
		this.projection.get(this.uploadBuffer);
		this.uploadBuffer.position(64);
		this.inverseProjection.get(this.uploadBuffer);
		this.uploadBuffer.position(128);
		putVec4(this.sunDirectionView.x, this.sunDirectionView.y, this.sunDirectionView.z, this.sunVisibility);
		putVec4(this.upDirectionView.x, this.upDirectionView.y, this.upDirectionView.z, this.rainFactor);
		putVec4(this.skyColor.x, this.skyColor.y, this.skyColor.z, fogIntensity(quality));
		putVec4(this.sunColor.x, this.sunColor.y, this.sunColor.z, shaftIntensity(quality));
		putVec4((float) this.screenWidth, (float) this.screenHeight, (float) sampleCount(quality), this.renderDistanceEnd);
		putVec4(this.renderDistanceStart, this.renderDistanceEnd, this.skyFogEnd, this.environmentalFogEnd);
		this.uploadBuffer.rewind();
		encoder.writeToBuffer(this.paramsSlice, this.uploadBuffer);
	}

	private void ensureGpuResources() {
		if (this.paramsBuffer != null) {
			return;
		}
		this.paramsBuffer = RenderSystem.getDevice().createBuffer(
				() -> "sulkan:atmosphere_params",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				PARAM_BYTES);
		this.paramsSlice = this.paramsBuffer.slice();
		this.linearSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
		this.depthSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));
	}

	private void putVec4(float x, float y, float z, float w) {
		this.uploadBuffer.putFloat(x);
		this.uploadBuffer.putFloat(y);
		this.uploadBuffer.putFloat(z);
		this.uploadBuffer.putFloat(w);
	}

	private static int sampleCount(ShaderQualityPreset quality) {
		return switch (quality) {
			case OFF -> 0;
			case LOW -> 3;
			case MEDIUM -> 4;
			case HIGH -> 6;
			case ULTRA -> 8;
		};
	}

	private static float fogIntensity(ShaderQualityPreset quality) {
		return switch (quality) {
			case OFF -> 0.0f;
			case LOW, MEDIUM, HIGH, ULTRA -> 1.0f;
		};
	}

	private static float shaftIntensity(ShaderQualityPreset quality) {
		return switch (quality) {
			case OFF -> 0.0f;
			case LOW -> 0.08f;
			case MEDIUM -> 0.10f;
			case HIGH, ULTRA -> 0.12f;
		};
	}

	private static void unpackRgb(int packedColor, Vector3f out) {
		out.set(((packedColor >> 16) & 0xFF) / 255.0f, ((packedColor >> 8) & 0xFF) / 255.0f, (packedColor & 0xFF) / 255.0f);
	}

	private static float clamp01(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float x = clamp01((value - edge0) / (edge1 - edge0));
		return x * x * (3.0f - 2.0f * x);
	}

	public static void close() {
		if (instance == null) {
			return;
		}
		if (instance.paramsBuffer != null) {
			instance.paramsBuffer.close();
		}
		if (instance.linearSampler != null) {
			instance.linearSampler.close();
		}
		if (instance.depthSampler != null) {
			instance.depthSampler.close();
		}
		instance.destroyShaftTarget();
		instance = null;
	}
}
