package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sulkan.shaders.config.ShaderQualityPreset;
import com.sulkan.shaders.render.sky.CustomCelestials;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DirectionalShadowRenderer {
	private static final int CASCADE_COUNT = 4;
	private static final int CASCADE_UBO_BYTES = 64 + 16 + 64;
	private static final int SHADOW_DATA_BYTES = 64 * CASCADE_COUNT + 16 * CASCADE_COUNT + 16 + 16 + 64;
	private static final Vector4f CLEAR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
	private static final ThreadLocal<Integer> ACTIVE_CASCADE = ThreadLocal.withInitial(() -> -1);
	private static DirectionalShadowRenderer instance;

	private final RenderTarget[] targets = new RenderTarget[CASCADE_COUNT];
	private final Matrix4f[] cascadeMatrices = new Matrix4f[CASCADE_COUNT];
	private final Vector3f[] frustumCorners = new Vector3f[8];
	private final float[] cascadeEnds = new float[CASCADE_COUNT];
	private final float[] cascadeTexelWorldSizes = new float[CASCADE_COUNT];
	private final int[] targetSizes = new int[CASCADE_COUNT];
	private final ByteBuffer cascadeUpload = ByteBuffer.allocateDirect(CASCADE_UBO_BYTES).order(ByteOrder.nativeOrder());
	private final ByteBuffer shadowUpload = ByteBuffer.allocateDirect(SHADOW_DATA_BYTES).order(ByteOrder.nativeOrder());
	private GpuBuffer cascadeBuffer;
	private GpuBuffer shadowDataBuffer;
	private GpuBufferSlice cascadeSlice;
	private GpuBufferSlice shadowDataSlice;
	private int activeCascadeCount;
	private final Vector3f lightDirection = new Vector3f(0.0f, -1.0f, 0.0f);
	private final Vector3f sunDirection = new Vector3f();
	private final Vector3f moonDirection = new Vector3f();
	private final Matrix4f inverseViewRotation = new Matrix4f();
	private float cameraX;
	private float cameraY;
	private float cameraZ;

	private DirectionalShadowRenderer() {
		for (int i = 0; i < CASCADE_COUNT; i++) {
			this.cascadeMatrices[i] = new Matrix4f();
		}
		for (int i = 0; i < this.frustumCorners.length; i++) {
			this.frustumCorners[i] = new Vector3f();
		}
	}

	public static DirectionalShadowRenderer get() {
		if (instance == null) {
			instance = new DirectionalShadowRenderer();
		}
		return instance;
	}

	public static boolean isRenderingShadowMap() {
		return ACTIVE_CASCADE.get() >= 0;
	}

	public static int activeCascade() {
		return ACTIVE_CASCADE.get();
	}

	public static void beginCascade(int cascade) {
		ACTIVE_CASCADE.set(cascade);
	}

	public static void endCascade() {
		ACTIVE_CASCADE.set(-1);
	}

	public void prepare(LevelRenderState levelRenderState) {
		ShaderQualityPreset quality = ShaderRuntime.config().shadowQualityPreset();
		if (!quality.enabled() || levelRenderState == null || levelRenderState.cameraRenderState == null) {
			this.activeCascadeCount = 0;
			return;
		}

		this.activeCascadeCount = switch (quality) {
			case LOW -> 1;
			case MEDIUM -> 3;
			case HIGH, ULTRA -> 4;
			case OFF -> 0;
		};
		this.ensureResources(targetSizes(quality));

		if (levelRenderState.skyRenderState != null) {
			CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.sunAngle, this.sunDirection);
			CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.moonAngle, this.moonDirection);
			this.lightDirection.set(this.sunDirection.y >= this.moonDirection.y ? this.sunDirection : this.moonDirection);
			if (this.lightDirection.y < 0.08f) {
				this.lightDirection.y = 0.08f;
			}
			this.lightDirection.normalize();
		} else {
			this.lightDirection.set(-0.35f, 0.82f, -0.44f).normalize();
		}
		if (levelRenderState.cameraRenderState.viewRotationMatrix != null) {
			this.inverseViewRotation.set(levelRenderState.cameraRenderState.viewRotationMatrix).invert();
		} else {
			this.inverseViewRotation.identity();
		}
		if (levelRenderState.cameraRenderState.pos != null) {
			this.cameraX = (float) levelRenderState.cameraRenderState.pos.x;
			this.cameraY = (float) levelRenderState.cameraRenderState.pos.y;
			this.cameraZ = (float) levelRenderState.cameraRenderState.pos.z;
		} else {
			this.cameraX = 0.0f;
			this.cameraY = 0.0f;
			this.cameraZ = 0.0f;
		}

		float coverage = Math.min(384.0f, Math.max(64.0f, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f));
		float[] splits = splitDistances(coverage, this.activeCascadeCount);
		Matrix4f inverseProjection = new Matrix4f(levelRenderState.cameraRenderState.projectionMatrix).invert();
		for (int i = 0; i < CASCADE_COUNT; i++) {
			float end = i < this.activeCascadeCount ? splits[i] : 0.0f;
			this.cascadeEnds[i] = end;
			if (i < this.activeCascadeCount) {
				float start = i == 0 ? 1.0f : splits[i - 1] * 0.78f;
				this.fitCascadeMatrix(i, inverseProjection, start, end, this.cascadeMatrices[i]);
			} else {
				this.cascadeMatrices[i].identity();
				this.cascadeTexelWorldSizes[i] = 0.0f;
			}
		}
	}

	public int activeCascadeCount() {
		return this.activeCascadeCount;
	}

	public float shadowDistance() {
		return this.activeCascadeCount <= 0 ? 0.0f : this.cascadeEnds[this.activeCascadeCount - 1];
	}

	public RenderTarget target(int cascade) {
		return cascade >= 0 && cascade < CASCADE_COUNT ? this.targets[cascade] : null;
	}

	public boolean sectionIntersectsCascade(int cascade, int originX, int originY, int originZ) {
		if (cascade < 0 || cascade >= this.activeCascadeCount) {
			return false;
		}
		Matrix4f matrix = this.cascadeMatrices[cascade];
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (int x = 0; x <= 16; x += 16) {
			for (int y = 0; y <= 16; y += 16) {
				for (int z = 0; z <= 16; z += 16) {
					Vector4f clip = matrix.transform(new Vector4f(
							originX + x - this.cameraX,
							originY + y - this.cameraY,
							originZ + z - this.cameraZ,
							1.0f));
					if (Math.abs(clip.w) < 1.0e-5f) {
						continue;
					}
					float invW = 1.0f / clip.w;
					float ndcX = clip.x * invW;
					float ndcY = clip.y * invW;
					float ndcZ = clip.z * invW;
					minX = Math.min(minX, ndcX);
					minY = Math.min(minY, ndcY);
					minZ = Math.min(minZ, ndcZ);
					maxX = Math.max(maxX, ndcX);
					maxY = Math.max(maxY, ndcY);
					maxZ = Math.max(maxZ, ndcZ);
				}
			}
		}
		float margin = cascade == 0 ? 0.18f : 0.12f;
		return maxX >= -1.0f - margin
				&& minX <= 1.0f + margin
				&& maxY >= -1.0f - margin
				&& minY <= 1.0f + margin
				&& maxZ >= -0.08f
				&& minZ <= 1.08f;
	}

	public void uploadCascade(CommandEncoder encoder, int cascade) {
		this.cascadeUpload.clear();
		this.cascadeMatrices[cascade].get(this.cascadeUpload);
		this.cascadeUpload.position(64);
		putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0f, this.cascadeUpload);
		this.cascadeUpload.position(80);
		this.inverseViewRotation.get(this.cascadeUpload);
		this.cascadeUpload.rewind();
		encoder.writeToBuffer(this.cascadeSlice, this.cascadeUpload);
	}

	public GpuBufferSlice cascadeSlice() {
		return this.cascadeSlice;
	}

	public void uploadShadowData(CommandEncoder encoder) {
		this.shadowUpload.clear();
		for (int i = 0; i < CASCADE_COUNT; i++) {
			this.cascadeMatrices[i].get(this.shadowUpload);
			this.shadowUpload.position((i + 1) * 64);
		}
		for (int i = 0; i < CASCADE_COUNT; i++) {
			float active = i < this.activeCascadeCount ? 1.0f : 0.0f;
			putVec4(this.cascadeTexelWorldSizes[i], i == 0 ? 0.0f : this.cascadeEnds[i - 1], this.cascadeEnds[i], active, this.shadowUpload);
		}
		float normalOffsetScale = receiverNormalOffsetScale(this.lightDirection.y);
		putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, normalOffsetScale, this.shadowUpload);
		putVec4(0.92f, 0.0f, 0.30f, 1.0f, this.shadowUpload);
		this.inverseViewRotation.get(this.shadowUpload);
		this.shadowUpload.rewind();
		encoder.writeToBuffer(this.shadowDataSlice, this.shadowUpload);
	}

	public GpuBufferSlice shadowDataSlice() {
		return this.shadowDataSlice;
	}

	public void clearCascade(CommandEncoder encoder, int cascade) {
		RenderTarget target = this.target(cascade);
		if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
			encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), 1.0);
		}
	}

	private void ensureResources(int[] sizes) {
		if (this.cascadeBuffer == null) {
			this.cascadeBuffer = RenderSystem.getDevice().createBuffer(() -> "sulkan:cascade_shadow_matrix", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, CASCADE_UBO_BYTES);
			this.shadowDataBuffer = RenderSystem.getDevice().createBuffer(() -> "sulkan:directional_shadow_data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SHADOW_DATA_BYTES);
			this.cascadeSlice = this.cascadeBuffer.slice();
			this.shadowDataSlice = this.shadowDataBuffer.slice();
		}
		boolean sameSizes = this.targets[0] != null;
		for (int i = 0; i < CASCADE_COUNT; i++) {
			sameSizes &= this.targetSizes[i] == sizes[i];
		}
		if (sameSizes) {
			return;
		}
		this.destroyTargets();
		for (int i = 0; i < CASCADE_COUNT; i++) {
			this.targetSizes[i] = sizes[i];
			RenderTargetDescriptor descriptor = new RenderTargetDescriptor(sizes[i], sizes[i], true, CLEAR, GpuFormat.RGBA8_UNORM);
			this.targets[i] = descriptor.allocate();
			descriptor.prepare(this.targets[i]);
		}
	}

	private void fitCascadeMatrix(int cascade, Matrix4f inverseProjection, float start, float end, Matrix4f destination) {
		this.buildFrustumSlice(inverseProjection, Math.max(0.25f, start), Math.max(start + 1.0f, end));

		Vector3f up = Math.abs(this.lightDirection.y) > 0.88f ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
		Vector3f lightRight = new Vector3f(up).cross(this.lightDirection).normalize();
		Vector3f lightUp = new Vector3f(this.lightDirection).cross(lightRight).normalize();

		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (Vector3f corner : this.frustumCorners) {
			float lightX = lightRight.dot(corner);
			float lightY = lightUp.dot(corner);
			float lightZ = this.lightDirection.dot(corner);
			minX = Math.min(minX, lightX);
			minY = Math.min(minY, lightY);
			minZ = Math.min(minZ, lightZ);
			maxX = Math.max(maxX, lightX);
			maxY = Math.max(maxY, lightY);
			maxZ = Math.max(maxZ, lightZ);
		}

		float padding = Math.max(8.0f, (end - start) * 0.08f);
		float halfExtent = Math.max((maxX - minX) * 0.5f, (maxY - minY) * 0.5f) + padding;
		float centerX = (minX + maxX) * 0.5f;
		float centerY = (minY + maxY) * 0.5f;
		float centerZ = (minZ + maxZ) * 0.5f;
		float texelSize = (halfExtent * 2.0f) / Math.max(1, this.targetSizes[cascade]);
		this.cascadeTexelWorldSizes[cascade] = texelSize;
		if (texelSize > 0.0f) {
			centerX = Math.round(centerX / texelSize) * texelSize;
			centerY = Math.round(centerY / texelSize) * texelSize;
		}

		float depthPadding = Math.max(80.0f, end * 0.75f);
		float shadowDepth = Math.max(64.0f, (maxZ - minZ) + depthPadding * 2.0f);
		Vector3f stableCenter = new Vector3f()
				.add(lightRight.x * centerX, lightRight.y * centerX, lightRight.z * centerX)
				.add(lightUp.x * centerY, lightUp.y * centerY, lightUp.z * centerY)
				.add(this.lightDirection.x * centerZ, this.lightDirection.y * centerZ, this.lightDirection.z * centerZ);
		Vector3f lightPos = new Vector3f(stableCenter).add(
				this.lightDirection.x * shadowDepth * 0.5f,
				this.lightDirection.y * shadowDepth * 0.5f,
				this.lightDirection.z * shadowDepth * 0.5f);
		Matrix4f lightView = new Matrix4f().lookAt(lightPos, stableCenter, lightUp);
		destination.identity()
				.ortho(-halfExtent, halfExtent, -halfExtent, halfExtent, 0.1f, shadowDepth, true)
				.mul(lightView);
	}

	private void buildFrustumSlice(Matrix4f inverseProjection, float start, float end) {
		int index = 0;
		for (float distance : new float[] { start, end }) {
			for (int y = -1; y <= 1; y += 2) {
				for (int x = -1; x <= 1; x += 2) {
					Vector4f view = inverseProjection.transform(new Vector4f((float) x, (float) y, 1.0f, 1.0f));
					view.div(view.w);
					Vector3f ray = new Vector3f(view.x, view.y, view.z);
					if (ray.lengthSquared() < 0.0001f) {
						ray.set((float) x, (float) y, -1.0f);
					}
					ray.normalize().mul(distance);
					this.inverseViewRotation.transformPosition(ray);
					this.frustumCorners[index++].set(ray);
				}
			}
		}
	}

	private void destroyTargets() {
		for (int i = 0; i < CASCADE_COUNT; i++) {
			if (this.targets[i] != null) {
				this.targets[i].destroyBuffers();
				this.targets[i] = null;
			}
			this.targetSizes[i] = 0;
			this.cascadeTexelWorldSizes[i] = 0.0f;
		}
	}

	private static float[] splitDistances(float coverage, int count) {
		float[] result = new float[CASCADE_COUNT];
		float near = 8.0f;
		for (int i = 0; i < count; i++) {
			float t = (float) (i + 1) / (float) count;
			float logarithmic = near * (float) Math.pow(coverage / near, t);
			float uniform = near + (coverage - near) * t;
			result[i] = uniform * 0.35f + logarithmic * 0.65f;
		}
		return result;
	}

	private static int[] targetSizes(ShaderQualityPreset quality) {
		return switch (quality) {
			case LOW -> new int[] {1536, 1024, 1024, 1024};
			case MEDIUM -> new int[] {2048, 1536, 1024, 1024};
			case HIGH -> new int[] {3072, 2048, 1536, 1024};
			case ULTRA -> new int[] {4096, 2048, 2048, 1024};
			case OFF -> new int[] {1024, 1024, 1024, 1024};
		};
	}

	private static float receiverNormalOffsetScale(float lightElevation) {
		float t = Math.max(0.0f, Math.min(1.0f, (lightElevation - 0.08f) / 0.92f));
		return 2.15f + (0.92f - 2.15f) * t;
	}

	private static void putVec4(float x, float y, float z, float w, ByteBuffer buffer) {
		buffer.putFloat(x);
		buffer.putFloat(y);
		buffer.putFloat(z);
		buffer.putFloat(w);
	}

	public static void close() {
		if (instance == null) {
			return;
		}
		instance.destroyTargets();
		if (instance.cascadeBuffer != null) {
			instance.cascadeBuffer.close();
		}
		if (instance.shadowDataBuffer != null) {
			instance.shadowDataBuffer.close();
		}
		instance = null;
	}
}
