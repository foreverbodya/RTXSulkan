package com.sulkan.shaders.render.cloud;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sulkan.shaders.config.ShaderQualityPreset;
import com.sulkan.shaders.render.ShaderRenderTargets;
import com.sulkan.shaders.render.sky.CustomCelestials;
import com.sulkan.shaders.runtime.ShaderPipelines;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

public final class VolumetricCloudRenderer {
	private static final int CLOUD_PARAMS_BYTES = 320;
	private static final int CLOUD_NOISE_SIZE = 256;

	private static VolumetricCloudRenderer instance;

	private final ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(CLOUD_PARAMS_BYTES).order(ByteOrder.nativeOrder());
	private final Matrix4f inverseProjection = new Matrix4f();
	private final Matrix4f inverseModelView = new Matrix4f();
	private final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);
	private final Vector3f moonDirection = new Vector3f(0.0f, -1.0f, 0.0f);
	private final Vector4f fogColor = new Vector4f(0.65f, 0.78f, 1.0f, 1.0f);

	private GpuBuffer paramsBuffer;
	private GpuBufferSlice paramsSlice;
	private GpuSampler linearSampler;
	private GpuSampler depthSampler;
	private GpuSampler noiseSampler;
	private GpuTexture cloudNoiseTexture;
	private GpuTextureView cloudNoiseTextureView;
	private RenderTarget cloudTraceTarget;

	private boolean preparedThisFrame;
	private boolean requestedThisFrame;
	private float preparedFogCloudEnd = 1024.0f;
	private float preparedSunVisibility = 1.0f;
	private float preparedMoonVisibility;
	private float preparedRainFactor;
	private int screenWidth = 1;
	private int screenHeight = 1;
	private int traceWidth = -1;
	private int traceHeight = -1;
	private int pendingCloudColor;
	private CloudStatus pendingCloudStatus = CloudStatus.FAST;
	private float pendingCloudHeight;
	private int pendingCloudRange;
	private Vec3 pendingCameraPos;
	private long pendingGameTime;
	private float pendingPartialTick;

	private VolumetricCloudRenderer() {
	}

	public static VolumetricCloudRenderer get() {
		if (instance == null) {
			instance = new VolumetricCloudRenderer();
		}
		return instance;
	}

	public static boolean isEnabled() {
		return ShaderRuntime.pipelineState().cloudsEnabled();
	}

	public void beginFrame(
			CameraRenderState cameraState,
			LevelRenderState levelRenderState,
			Matrix4fc projectionMatrix,
			Matrix4fc modelViewMatrix,
			Vector4f fallbackFogColor,
			RenderTarget mainTarget) {
		this.requestedThisFrame = false;
		this.pendingCameraPos = null;

		if (!isEnabled() || cameraState == null) {
			this.preparedThisFrame = false;
			return;
		}

		this.ensureGpuResources();
		if (projectionMatrix != null) {
			this.inverseProjection.set(projectionMatrix).invert();
		} else if (cameraState.projectionMatrix != null) {
			this.inverseProjection.set(cameraState.projectionMatrix).invert();
		} else {
			this.inverseProjection.identity();
		}

		if (modelViewMatrix != null) {
			this.inverseModelView.set(modelViewMatrix).invert();
		} else if (cameraState.viewRotationMatrix != null) {
			this.inverseModelView.set(cameraState.viewRotationMatrix).invert();
		} else {
			this.inverseModelView.identity();
		}

		if (cameraState.fogData != null) {
			this.preparedFogCloudEnd = Math.max(64.0f, cameraState.fogData.cloudEnd);
			if (cameraState.fogData.color != null) {
				this.fogColor.set(cameraState.fogData.color);
			} else if (fallbackFogColor != null) {
				this.fogColor.set(fallbackFogColor);
			}
		} else if (fallbackFogColor != null) {
			this.fogColor.set(fallbackFogColor);
			this.preparedFogCloudEnd = 1024.0f;
		}

		this.sunDirection.set(0.0f, 1.0f, 0.0f);
		this.moonDirection.set(0.0f, -1.0f, 0.0f);
		this.preparedSunVisibility = 1.0f;
		this.preparedMoonVisibility = 0.0f;
		this.preparedRainFactor = 0.0f;
		if (levelRenderState != null && levelRenderState.skyRenderState != null) {
			CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.sunAngle, this.sunDirection);
			CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.moonAngle, this.moonDirection);
			float sunElevation = this.sunDirection.y;
			this.preparedSunVisibility = clamp01((sunElevation + 0.08f) / 0.24f);
			this.preparedMoonVisibility = clamp01((-sunElevation + 0.04f) / 0.22f);
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.level != null) {
			this.preparedRainFactor = clamp01(minecraft.level.getRainLevel(1.0f));
		}

		if (mainTarget != null) {
			this.screenWidth = Math.max(1, mainTarget.width);
			this.screenHeight = Math.max(1, mainTarget.height);
		}
		this.preparedThisFrame = true;
	}

	public boolean request(int cloudColor, CloudStatus cloudStatus, float cloudHeight, int cloudRange, Vec3 cameraPos, long gameTime, float partialTick) {
		if (!this.preparedThisFrame
				|| cloudStatus == CloudStatus.OFF
				|| cameraPos == null
				|| ShaderPipelines.VOLUMETRIC_CLOUDS == null
				|| ShaderPipelines.VOLUMETRIC_CLOUDS_COMPOSITE == null) {
			return false;
		}

		this.pendingCloudColor = cloudColor;
		this.pendingCloudStatus = cloudStatus;
		this.pendingCloudHeight = cloudHeight;
		this.pendingCloudRange = cloudRange;
		this.pendingCameraPos = cameraPos;
		this.pendingGameTime = gameTime;
		this.pendingPartialTick = partialTick;
		this.requestedThisFrame = true;
		return true;
	}

	public boolean isRequestedThisFrame() {
		return this.preparedThisFrame && this.requestedThisFrame && this.pendingCameraPos != null;
	}

	public boolean execute(CommandEncoder encoder, RenderTarget sourceColorTarget, RenderTarget outputTarget, RenderTarget depthTarget) {
		if (encoder == null || !this.canExecute(sourceColorTarget, outputTarget, depthTarget)) {
			return false;
		}

		this.ensureGpuResources();
		this.ensureTraceTarget(this.traceWidth(), this.traceHeight());
		if (this.cloudTraceTarget == null || this.cloudTraceTarget.getColorTextureView() == null) {
			return false;
		}

		this.upload(encoder);
		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Volumetric Cloud Trace",
				this.cloudTraceTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.VOLUMETRIC_CLOUDS);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("CloudNoiseSampler", this.cloudNoiseTextureView, this.noiseSampler);
			pass.bindTexture("SceneDepthSampler", depthTarget.getDepthTextureView(), this.depthSampler);
			pass.setUniform("CloudParams", this.paramsSlice);
			pass.draw(3, 1, 0, 0);
		}

		try (RenderPass pass = encoder.createRenderPass(
				() -> "Sulkan Volumetric Cloud Composite",
				outputTarget.getColorTextureView(),
				Optional.empty(),
				null,
				OptionalDouble.empty())) {
			pass.setPipeline(ShaderPipelines.VOLUMETRIC_CLOUDS_COMPOSITE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("SceneColorSampler", sourceColorTarget.getColorTextureView(), this.depthSampler);
			pass.bindTexture("SceneDepthSampler", depthTarget.getDepthTextureView(), this.depthSampler);
			pass.bindTexture("CloudTraceSampler", this.cloudTraceTarget.getColorTextureView(), this.linearSampler);
			pass.draw(3, 1, 0, 0);
		}

		this.requestedThisFrame = false;
		return true;
	}

	private boolean canExecute(RenderTarget sourceColorTarget, RenderTarget outputTarget, RenderTarget depthTarget) {
		return this.preparedThisFrame
				&& this.requestedThisFrame
				&& this.pendingCameraPos != null
				&& sourceColorTarget != null
				&& sourceColorTarget.getColorTexture() != null
				&& outputTarget != null
				&& outputTarget.getColorTexture() != null
				&& depthTarget != null
				&& depthTarget.getDepthTexture() != null
				&& ShaderPipelines.VOLUMETRIC_CLOUDS != null
				&& ShaderPipelines.VOLUMETRIC_CLOUDS_COMPOSITE != null;
	}

	private void upload(CommandEncoder encoder) {
		ShaderQualityPreset quality = ShaderRuntime.config().cloudQualityPreset();
		Vec3 cameraPos = this.pendingCameraPos;
		if (cameraPos == null) {
			return;
		}

		float maxSteps = switch (quality) {
			case LOW -> 10.0f;
			case MEDIUM -> 14.0f;
			case HIGH -> 20.0f;
			case ULTRA -> 28.0f;
			case OFF -> 0.0f;
		};
		float stepLength = switch (quality) {
			case LOW -> 88.0f;
			case MEDIUM -> 72.0f;
			case HIGH -> 56.0f;
			case ULTRA -> 42.0f;
			case OFF -> 0.0f;
		};
		float detailMix = switch (quality) {
			case LOW -> 0.22f;
			case MEDIUM -> 0.34f;
			case HIGH -> 0.46f;
			case ULTRA -> 0.58f;
			case OFF -> 0.0f;
		};
		float maxDistance = Math.max(256.0f, Math.min(this.preparedFogCloudEnd * 1.08f, this.pendingCloudRange * 16.0f));
		float cloudTime = (this.pendingGameTime + this.pendingPartialTick) * 0.0022f;
		float cloudR = ((this.pendingCloudColor >> 16) & 0xFF) / 255.0f;
		float cloudG = ((this.pendingCloudColor >> 8) & 0xFF) / 255.0f;
		float cloudB = (this.pendingCloudColor & 0xFF) / 255.0f;
		if (((this.pendingCloudColor >>> 24) & 0xFF) <= 1) {
			cloudR = Math.max(cloudR, 0.76f);
			cloudG = Math.max(cloudG, 0.80f);
			cloudB = Math.max(cloudB, 0.86f);
		}

		float rainCoverageBoost = this.preparedRainFactor * 0.03f;
		float lowLayerAltitude = this.pendingCloudHeight - 14.0f;
		float lowLayerThickness = switch (quality) {
			case LOW -> 22.0f;
			case MEDIUM -> 28.0f;
			case HIGH -> 34.0f;
			case ULTRA -> 38.0f;
			case OFF -> 0.0f;
		} + this.preparedRainFactor * 8.0f;
		float highLayerAltitude = this.pendingCloudHeight + 96.0f;
		float highLayerThickness = switch (quality) {
			case LOW -> 10.0f;
			case MEDIUM -> 12.0f;
			case HIGH -> 14.0f;
			case ULTRA -> 16.0f;
			case OFF -> 0.0f;
		};
		float lowLayerCoverage = switch (quality) {
			case LOW -> 0.76f;
			case MEDIUM -> 0.72f;
			case HIGH -> 0.68f;
			case ULTRA -> 0.64f;
			case OFF -> 1.0f;
		} - rainCoverageBoost;
		float highLayerCoverage = switch (quality) {
			case LOW -> 0.87f;
			case MEDIUM -> 0.83f;
			case HIGH -> 0.79f;
			case ULTRA -> 0.75f;
			case OFF -> 1.0f;
		} - this.preparedRainFactor * 0.015f;
		float fancyFlag = this.pendingCloudStatus == CloudStatus.FANCY ? 1.0f : 0.0f;

		this.uploadBuffer.clear();
		this.inverseProjection.get(this.uploadBuffer);
		this.uploadBuffer.position(64);
		this.inverseModelView.get(this.uploadBuffer);
		this.uploadBuffer.position(128);
		putVec4((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z, cloudTime);
		putVec4(cloudR, cloudG, cloudB, this.preparedRainFactor);
		putVec4(this.sunDirection.x, this.sunDirection.y, this.sunDirection.z, this.preparedSunVisibility);
		putVec4(this.moonDirection.x, this.moonDirection.y, this.moonDirection.z, this.preparedMoonVisibility);
		putVec4(this.fogColor.x, this.fogColor.y, this.fogColor.z, this.preparedFogCloudEnd);
		putVec4((float) this.screenWidth, (float) this.screenHeight, (float) this.traceWidth, (float) this.traceHeight);
		putVec4(maxSteps, stepLength, detailMix, fancyFlag);
		putVec4(lowLayerAltitude, lowLayerThickness, maxDistance, lowLayerCoverage);
		putVec4(0.00076f, 4.0f + detailMix * 2.4f, 0.82f, 0.28f);
		putVec4(highLayerAltitude, highLayerThickness, maxDistance * 1.12f, highLayerCoverage);
		putVec4(0.00102f, 5.2f + detailMix * 2.8f, 0.54f, 0.42f);
		putVec4(0.22f, 0.58f, 0.0f, 0.0f);
		this.uploadBuffer.rewind();
		encoder.writeToBuffer(this.paramsSlice, this.uploadBuffer);
	}

	private void ensureGpuResources() {
		if (this.paramsBuffer != null) {
			return;
		}

		this.paramsBuffer = RenderSystem.getDevice().createBuffer(
				() -> "sulkan:volumetric_cloud_params",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				CLOUD_PARAMS_BYTES);
		this.paramsSlice = this.paramsBuffer.slice();
		this.linearSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
		this.depthSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));
		this.noiseSampler = RenderSystem.getDevice().createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
		this.createCloudNoiseTexture();
	}

	private void createCloudNoiseTexture() {
		byte[] noiseData = new byte[CLOUD_NOISE_SIZE * CLOUD_NOISE_SIZE * 4];
		for (int y = 0; y < CLOUD_NOISE_SIZE; y++) {
			for (int x = 0; x < CLOUD_NOISE_SIZE; x++) {
				float u = (float) x / (float) CLOUD_NOISE_SIZE;
				float v = (float) y / (float) CLOUD_NOISE_SIZE;
				float broad = tileableFbm(u * 4.0f + 1.3f, v * 4.0f - 2.1f, 4, 3);
				float cells = tileableFbm(u * 9.0f - 4.7f, v * 9.0f + 3.6f, 9, 2);
				float detail = 1.0f - Math.abs(tileableFbm(u * 18.0f + 2.9f, v * 18.0f - 6.4f, 18, 2) * 2.0f - 1.0f);
				float weather = tileableFbm(u * 2.0f + 7.1f, v * 2.0f - 5.2f, 2, 3);
				int index = (y * CLOUD_NOISE_SIZE + x) * 4;
				noiseData[index] = (byte) toByte(broad);
				noiseData[index + 1] = (byte) toByte(cells);
				noiseData[index + 2] = (byte) toByte(detail);
				noiseData[index + 3] = (byte) toByte(weather);
			}
		}

		this.cloudNoiseTexture = RenderSystem.getDevice().createTexture(
				() -> "sulkan:cloud_noise",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM,
				CLOUD_NOISE_SIZE,
				CLOUD_NOISE_SIZE,
				1,
				1);
		ByteBuffer noiseBuffer = ByteBuffer.allocateDirect(noiseData.length).order(ByteOrder.nativeOrder());
		noiseBuffer.put(noiseData).rewind();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.writeToTexture(this.cloudNoiseTexture, noiseBuffer, 0, 0, 0, 0, CLOUD_NOISE_SIZE, CLOUD_NOISE_SIZE);
		encoder.submit();
		this.cloudNoiseTextureView = RenderSystem.getDevice().createTextureView(this.cloudNoiseTexture);
	}

	private void ensureTraceTarget(int width, int height) {
		if (this.cloudTraceTarget != null && this.traceWidth == width && this.traceHeight == height) {
			return;
		}
		this.destroyTraceTarget();
		var descriptor = ShaderRenderTargets.colorOnly(width, height);
		this.cloudTraceTarget = descriptor.allocate();
		descriptor.prepare(this.cloudTraceTarget);
		this.traceWidth = width;
		this.traceHeight = height;
	}

	private void destroyTraceTarget() {
		if (this.cloudTraceTarget != null) {
			this.cloudTraceTarget.destroyBuffers();
			this.cloudTraceTarget = null;
		}
		this.traceWidth = -1;
		this.traceHeight = -1;
	}

	private int traceWidth() {
		return Math.max(1, this.screenWidth / traceDivisor());
	}

	private int traceHeight() {
		return Math.max(1, this.screenHeight / traceDivisor());
	}

	private static int traceDivisor() {
		return switch (ShaderRuntime.config().cloudQualityPreset()) {
			case LOW -> 4;
			case MEDIUM, HIGH -> 2;
			case ULTRA, OFF -> 1;
		};
	}

	private void putVec4(float x, float y, float z, float w) {
		this.uploadBuffer.putFloat(x);
		this.uploadBuffer.putFloat(y);
		this.uploadBuffer.putFloat(z);
		this.uploadBuffer.putFloat(w);
	}

	private static int toByte(float value) {
		return Math.max(0, Math.min(255, (int) (clamp01(value) * 255.0f + 0.5f)));
	}

	private static float tileableFbm(float x, float y, int period, int octaves) {
		float value = 0.0f;
		float amplitude = 0.58f;
		float normalizer = 0.0f;
		float sampleX = x;
		float sampleY = y;
		int currentPeriod = period;
		for (int octave = 0; octave < octaves; octave++) {
			value += tileableValueNoise(sampleX, sampleY, currentPeriod) * amplitude;
			normalizer += amplitude;
			sampleX = sampleX * 2.0f + 17.3f;
			sampleY = sampleY * 2.0f - 11.7f;
			currentPeriod *= 2;
			amplitude *= 0.5f;
		}
		return normalizer > 0.0f ? value / normalizer : 0.0f;
	}

	private static float tileableValueNoise(float x, float y, int period) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		float fx = smooth01(x - x0);
		float fy = smooth01(y - y0);
		float a = tileableHash(x0, y0, period);
		float b = tileableHash(x0 + 1, y0, period);
		float c = tileableHash(x0, y0 + 1, period);
		float d = tileableHash(x0 + 1, y0 + 1, period);
		float xBlend0 = a + (b - a) * fx;
		float xBlend1 = c + (d - c) * fx;
		return xBlend0 + (xBlend1 - xBlend0) * fy;
	}

	private static float tileableHash(int x, int y, int period) {
		int wrappedX = Math.floorMod(x, Math.max(period, 1));
		int wrappedY = Math.floorMod(y, Math.max(period, 1));
		long bits = 0x9E3779B97F4A7C15L;
		bits ^= (long) wrappedX * 0xC2B2AE3D27D4EB4FL;
		bits ^= (long) wrappedY * 0x165667B19E3779F9L;
		bits ^= bits >>> 33;
		bits *= 0xFF51AFD7ED558CCDL;
		bits ^= bits >>> 33;
		bits *= 0xC4CEB9FE1A85EC53L;
		bits ^= bits >>> 33;
		return (bits & 0xFFFFFFL) / (float) 0x1000000;
	}

	private static float smooth01(float value) {
		return value * value * (3.0f - 2.0f * value);
	}

	private static float clamp01(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}

	public static void close() {
		if (instance == null) {
			return;
		}
		instance.destroyTraceTarget();
		if (instance.paramsBuffer != null) {
			instance.paramsBuffer.close();
		}
		if (instance.linearSampler != null) {
			instance.linearSampler.close();
		}
		if (instance.depthSampler != null) {
			instance.depthSampler.close();
		}
		if (instance.noiseSampler != null) {
			instance.noiseSampler.close();
		}
		if (instance.cloudNoiseTextureView != null) {
			instance.cloudNoiseTextureView.close();
		}
		if (instance.cloudNoiseTexture != null) {
			instance.cloudNoiseTexture.close();
		}
		instance = null;
	}
}
