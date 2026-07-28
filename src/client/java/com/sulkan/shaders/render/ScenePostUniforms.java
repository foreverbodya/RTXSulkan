package com.sulkan.shaders.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ScenePostUniforms {
	private static final int PARAM_BYTES = 64 + 64 + 16 * 4 + 64 + 16;
	private static final float ORBIT_YAW_RADIANS = (float) Math.toRadians(-45.0F);
	private static final float ORBIT_YAW_SIN = (float) Math.sin(ORBIT_YAW_RADIANS);
	private static final float ORBIT_YAW_COS = (float) Math.cos(ORBIT_YAW_RADIANS);
	private static final Vector3f WORLD_UP = new Vector3f(0.0F, 1.0F, 0.0F);

	private static final Matrix4f projection = new Matrix4f();
	private static final Matrix4f inverseProjection = new Matrix4f();
	private static final Matrix4f modelView = new Matrix4f();
	private static final Matrix4f inverseViewRotation = new Matrix4f();
	private static final Vector3f sunDirection = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f sunDirectionView = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f upDirectionView = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f fogColor = new Vector3f(0.58F, 0.68F, 0.86F);
	private static final ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(PARAM_BYTES).order(ByteOrder.nativeOrder());

	private static GpuBuffer paramsBuffer;
	private static GpuBufferSlice paramsSlice;
	private static int screenWidth = 1;
	private static int screenHeight = 1;
	private static float sunVisibility = 1.0F;
	private static float rainFactor;
	private static float fogIntensity = 0.65F;
	private static float timeSeconds;
	private static float cameraX;
	private static float cameraY;
	private static float cameraZ;
	private static float cameraUnderwater;

	private ScenePostUniforms() {
	}

	public static void prepare(
			CameraRenderState cameraState,
			LevelRenderState levelRenderState,
			Matrix4fc modelViewMatrix,
			Vector4f fallbackFogColor,
			RenderTarget mainTarget,
			DeltaTracker deltaTracker) {
		if (cameraState != null && cameraState.projectionMatrix != null) {
			projection.set(cameraState.projectionMatrix);
			inverseProjection.set(projection).invert();
		} else {
			projection.identity();
			inverseProjection.identity();
		}

		if (modelViewMatrix != null) {
			modelView.set(modelViewMatrix);
		} else if (cameraState != null && cameraState.viewRotationMatrix != null) {
			modelView.set(cameraState.viewRotationMatrix);
		} else {
			modelView.identity();
		}
		if (cameraState != null && cameraState.viewRotationMatrix != null) {
			inverseViewRotation.set(cameraState.viewRotationMatrix).invert();
		} else {
			inverseViewRotation.set(modelView).invert();
		}
		if (cameraState != null && cameraState.pos != null) {
			Vec3 pos = cameraState.pos;
			cameraX = (float) pos.x;
			cameraY = (float) pos.y;
			cameraZ = (float) pos.z;
		} else {
			cameraX = 0.0F;
			cameraY = 0.0F;
			cameraZ = 0.0F;
		}

		if (mainTarget != null) {
			screenWidth = Math.max(1, mainTarget.width);
			screenHeight = Math.max(1, mainTarget.height);
		}

		SkyRenderState sky = levelRenderState == null ? null : levelRenderState.skyRenderState;
		if (sky != null) {
			setCelestialDirection(sky.sunAngle, sunDirection);
			unpackRgb(sky.skyColor, fogColor);
		} else {
			sunDirection.set(0.0F, 1.0F, 0.0F);
			fogColor.set(0.58F, 0.68F, 0.86F);
		}

		if (fallbackFogColor != null) {
			fogColor.lerp(new Vector3f(fallbackFogColor.x, fallbackFogColor.y, fallbackFogColor.z), 0.35F);
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.level != null) {
			rainFactor = clamp01(minecraft.level.getRainLevel(1.0F));
			float partialTick = deltaTracker == null ? 0.0F : deltaTracker.getGameTimeDeltaPartialTick(false);
			timeSeconds = ((minecraft.level.getGameTime() % 24000L) + partialTick) / 20.0F;
			Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.mainCamera();
			cameraUnderwater = camera != null && camera.getFluidInCamera() == FogType.WATER ? 1.0F : 0.0F;
		} else {
			rainFactor = 0.0F;
			timeSeconds = 0.0F;
			cameraUnderwater = 0.0F;
		}

		sunVisibility = clamp01((sunDirection.y + 0.08F) / 0.24F) * (1.0F - rainFactor * 0.68F);
		modelView.transformDirection(sunDirection, sunDirectionView).normalize();
		modelView.transformDirection(WORLD_UP, upDirectionView).normalize();
		fogIntensity = cameraState != null && cameraState.fogData != null ? 0.85F : 0.55F;
	}

	public static GpuBufferSlice upload(CommandEncoder encoder) {
		ensureBuffer();
		uploadBuffer.clear();
		projection.get(uploadBuffer);
		uploadBuffer.position(64);
		inverseProjection.get(uploadBuffer);
		uploadBuffer.position(128);
		putVec4(sunDirectionView.x, sunDirectionView.y, sunDirectionView.z, sunVisibility);
		putVec4(upDirectionView.x, upDirectionView.y, upDirectionView.z, rainFactor);
		putVec4((float) screenWidth, (float) screenHeight, timeSeconds, ShaderRuntime.config().waterQualityPreset().tier());
		putVec4(fogColor.x, fogColor.y, fogColor.z, fogIntensity);
		inverseViewRotation.get(uploadBuffer);
		uploadBuffer.position(256);
		putVec4(cameraX, cameraY, cameraZ, cameraUnderwater);
		uploadBuffer.rewind();
		encoder.writeToBuffer(paramsSlice, uploadBuffer);
		return paramsSlice;
	}

	public static void close() {
		if (paramsBuffer != null) {
			paramsBuffer.close();
			paramsBuffer = null;
			paramsSlice = null;
		}
	}

	private static void ensureBuffer() {
		if (paramsBuffer != null) {
			return;
		}
		paramsBuffer = RenderSystem.getDevice().createBuffer(
				() -> "sulkan:scene_post_params",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				PARAM_BYTES);
		paramsSlice = paramsBuffer.slice();
	}

	private static void setCelestialDirection(float angle, Vector3f out) {
		float horizontal = (float) Math.sin(angle);
		out.set(horizontal * ORBIT_YAW_SIN, (float) Math.cos(angle), horizontal * ORBIT_YAW_COS);
	}

	private static void unpackRgb(int packedRgb, Vector3f out) {
		out.set(
				((packedRgb >> 16) & 255) / 255.0F,
				((packedRgb >> 8) & 255) / 255.0F,
				(packedRgb & 255) / 255.0F);
	}

	private static void putVec4(float x, float y, float z, float w) {
		uploadBuffer.putFloat(x);
		uploadBuffer.putFloat(y);
		uploadBuffer.putFloat(z);
		uploadBuffer.putFloat(w);
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
