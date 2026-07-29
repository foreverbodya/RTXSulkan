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
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DirectionalShadowRenderer {
	private static final int CASCADE_COUNT = 4;
	private static final int ENTITY_CASCADE_COUNT = 2;
	private static final int[] CASCADE_UPDATE_INTERVALS = {3, 8, 16, 32};
	private static final int[] ENTITY_UPDATE_INTERVALS = {1, 2, 4, 8};
	private static final double[] CASCADE_MOVEMENT_LIMITS = {1.5, 4.0, 12.0, 24.0};
	private static final int CASCADE_UBO_BYTES = 64 + 16 + 64;
	private static final int SHADOW_DATA_BYTES = 64 * CASCADE_COUNT + 16 * CASCADE_COUNT + 16 + 16 + 64;
	private static final Vector4f CLEAR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
	private static final ThreadLocal<Integer> ACTIVE_CASCADE = ThreadLocal.withInitial(() -> -1);
	private static final ThreadLocal<Boolean> ACTIVE_ENTITY_PASS = ThreadLocal.withInitial(() -> false);
	private static DirectionalShadowRenderer instance;

	private final RenderTarget[] targets = new RenderTarget[CASCADE_COUNT];
	private final RenderTarget[] entityTargets = new RenderTarget[CASCADE_COUNT];
	private final Matrix4f[] cascadeMatrices = new Matrix4f[CASCADE_COUNT];
	private final Matrix4f[] renderedCascadeMatrices = new Matrix4f[CASCADE_COUNT];
	private final Vector3f[] frustumCorners = new Vector3f[8];
	private final Vector3f[] renderedLightDirections = new Vector3f[CASCADE_COUNT];
	private final Vector3f[] renderedCameraForwards = new Vector3f[CASCADE_COUNT];
	private final float[] cascadeEnds = new float[CASCADE_COUNT];
	private final float[] cascadeTexelWorldSizes = new float[CASCADE_COUNT];
	private final float[] cascadeDepthRanges = new float[CASCADE_COUNT];
	private final int[] targetSizes = new int[CASCADE_COUNT];
	private final int[] entityTargetSizes = new int[CASCADE_COUNT];
	private final boolean[] cascadeUpdates = new boolean[CASCADE_COUNT];
	private final boolean[] cascadeInitialized = new boolean[CASCADE_COUNT];
	private final boolean[] entityCascadeUpdates = new boolean[CASCADE_COUNT];
	private final boolean[] entityCascadeInitialized = new boolean[CASCADE_COUNT];
	private final double[] renderedCameraX = new double[CASCADE_COUNT];
	private final double[] renderedCameraY = new double[CASCADE_COUNT];
	private final double[] renderedCameraZ = new double[CASCADE_COUNT];
	private final long[] renderedFrameSerial = new long[CASCADE_COUNT];
	private final long[] renderedTerrainRevision = new long[CASCADE_COUNT];
	private final long[] renderedEntityFrameSerial = new long[CASCADE_COUNT];
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
	private final Vector3f cameraForward = new Vector3f(0.0f, 0.0f, -1.0f);
	private final Matrix4f inverseViewRotation = new Matrix4f();
	private long frameSerial;
	private float lastCoverage = -1.0f;
	private int lastActiveCascadeCount = -1;
	private double cameraX;
	private double cameraY;
	private double cameraZ;

	private DirectionalShadowRenderer() {
		for (int i = 0; i < CASCADE_COUNT; i++) {
			this.cascadeMatrices[i] = new Matrix4f();
			this.renderedCascadeMatrices[i] = new Matrix4f();
			this.renderedLightDirections[i] = new Vector3f();
			this.renderedCameraForwards[i] = new Vector3f();
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
		ACTIVE_ENTITY_PASS.set(false);
	}

	public static void beginEntityCascade(int cascade) {
		ACTIVE_CASCADE.set(cascade);
		ACTIVE_ENTITY_PASS.set(true);
	}

	public static void endCascade() {
		ACTIVE_CASCADE.set(-1);
		ACTIVE_ENTITY_PASS.set(false);
	}

	public void prepare(LevelRenderState levelRenderState) {
		ShaderQualityPreset quality = ShaderRuntime.config().shadowQualityPreset();
		if (!quality.enabled() || levelRenderState == null || levelRenderState.cameraRenderState == null) {
			this.activeCascadeCount = 0;
			return;
		}

		int nextActiveCascadeCount = switch (quality) {
			case LOW -> 1;
			case MEDIUM -> 3;
			case HIGH, ULTRA -> 4;
			case OFF -> 0;
		};
		boolean resourcesChanged = this.ensureResources(targetSizes(quality), quality);
		this.activeCascadeCount = nextActiveCascadeCount;
		this.frameSerial++;

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
			this.cameraForward.set(0.0f, 0.0f, -1.0f);
			this.inverseViewRotation.transformDirection(this.cameraForward).normalize();
		} else {
			this.inverseViewRotation.identity();
			this.cameraForward.set(0.0f, 0.0f, -1.0f);
		}
		if (levelRenderState.cameraRenderState.pos != null) {
			this.cameraX = levelRenderState.cameraRenderState.pos.x;
			this.cameraY = levelRenderState.cameraRenderState.pos.y;
			this.cameraZ = levelRenderState.cameraRenderState.pos.z;
		} else {
			this.cameraX = 0.0f;
			this.cameraY = 0.0f;
			this.cameraZ = 0.0f;
		}

		float coverage = Math.min(384.0f, Math.max(64.0f, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f));
		boolean layoutChanged = resourcesChanged
				|| this.lastActiveCascadeCount != this.activeCascadeCount
				|| Math.abs(this.lastCoverage - coverage) > 0.5f;
		this.lastActiveCascadeCount = this.activeCascadeCount;
		this.lastCoverage = coverage;
		float[] splits = splitDistances(coverage, this.activeCascadeCount);
		Matrix4f inverseProjection = new Matrix4f(levelRenderState.cameraRenderState.projectionMatrix).invert();
		for (int i = 0; i < CASCADE_COUNT; i++) {
			float end = i < this.activeCascadeCount ? splits[i] : 0.0f;
			if (i < this.activeCascadeCount) {
				boolean update = this.shouldUpdateCascade(i, layoutChanged);
				this.cascadeUpdates[i] = update;
				if (update) {
					float start = i == 0 ? 0.5f : splits[i - 1] * 0.82f;
					this.cascadeEnds[i] = end;
					this.fitCascadeMatrix(i, inverseProjection, start, end, this.renderedCascadeMatrices[i]);
					this.renderedCameraX[i] = this.cameraX;
					this.renderedCameraY[i] = this.cameraY;
					this.renderedCameraZ[i] = this.cameraZ;
					this.renderedFrameSerial[i] = this.frameSerial;
					this.renderedTerrainRevision[i] = SodiumShadowTerrainRenderer.terrainRevision();
					this.renderedLightDirections[i].set(this.lightDirection);
					this.renderedCameraForwards[i].set(this.cameraForward);
					this.cascadeInitialized[i] = true;
				}
				this.cascadeMatrices[i]
						.set(this.renderedCascadeMatrices[i])
						.translate(
								(float) (this.cameraX - this.renderedCameraX[i]),
								(float) (this.cameraY - this.renderedCameraY[i]),
								(float) (this.cameraZ - this.renderedCameraZ[i]));
				boolean entityUpdate = i < ENTITY_CASCADE_COUNT && (layoutChanged
						|| !this.entityCascadeInitialized[i]
						|| update
						|| this.frameSerial - this.renderedEntityFrameSerial[i] >= ENTITY_UPDATE_INTERVALS[i]);
				this.entityCascadeUpdates[i] = entityUpdate;
				if (entityUpdate) {
					this.renderedEntityFrameSerial[i] = this.frameSerial;
					this.entityCascadeInitialized[i] = true;
				}
			} else {
				this.cascadeUpdates[i] = false;
				this.cascadeInitialized[i] = false;
				this.entityCascadeUpdates[i] = false;
				this.entityCascadeInitialized[i] = false;
				this.cascadeEnds[i] = 0.0f;
				this.cascadeMatrices[i].identity();
				this.renderedCascadeMatrices[i].identity();
				this.cascadeTexelWorldSizes[i] = 0.0f;
				this.cascadeDepthRanges[i] = 0.0f;
			}
		}
	}

	public int activeCascadeCount() {
		return this.activeCascadeCount;
	}

	public Matrix4fc cascadeMatrix(int cascade) {
		return this.cascadeMatrices[cascade];
	}

	public boolean shouldUpdateCascade(int cascade) {
		return cascade >= 0 && cascade < this.activeCascadeCount && this.cascadeUpdates[cascade];
	}

	public boolean shouldUpdateEntityCascade(int cascade) {
		return cascade >= 0 && cascade < this.activeCascadeCount && this.entityCascadeUpdates[cascade];
	}

	public float cascadeEnd(int cascade) {
		return cascade >= 0 && cascade < this.activeCascadeCount ? this.cascadeEnds[cascade] : 0.0f;
	}

	public Vector3f lightDirection(Vector3f destination) {
		return destination.set(this.lightDirection);
	}

	public Vector3f cameraForward(Vector3f destination) {
		return destination.set(this.cameraForward);
	}

	public float shadowDistance() {
		return this.activeCascadeCount <= 0 ? 0.0f : this.cascadeEnds[this.activeCascadeCount - 1];
	}

	public float entityShadowDistance() {
		if (this.activeCascadeCount <= 0) {
			return 0.0f;
		}
		return this.cascadeEnds[Math.min(ENTITY_CASCADE_COUNT, this.activeCascadeCount) - 1];
	}

	public float entityCascadeEnd(int cascade) {
		return cascade >= 0 && cascade < Math.min(ENTITY_CASCADE_COUNT, this.activeCascadeCount)
				? this.cascadeEnds[cascade]
				: 0.0f;
	}

	public RenderTarget target(int cascade) {
		return cascade >= 0 && cascade < CASCADE_COUNT ? this.targets[cascade] : null;
	}

	public RenderTarget entityTarget(int cascade) {
		return cascade >= 0 && cascade < CASCADE_COUNT ? this.entityTargets[cascade] : null;
	}

	public RenderTarget activeTarget() {
		return ACTIVE_ENTITY_PASS.get() ? this.entityTarget(activeCascade()) : this.target(activeCascade());
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
					float relativeX = (float) (originX + x - this.cameraX);
					float relativeY = (float) (originY + y - this.cameraY);
					float relativeZ = (float) (originZ + z - this.cameraZ);
					float clipX = matrix.m00() * relativeX + matrix.m10() * relativeY + matrix.m20() * relativeZ + matrix.m30();
					float clipY = matrix.m01() * relativeX + matrix.m11() * relativeY + matrix.m21() * relativeZ + matrix.m31();
					float clipZ = matrix.m02() * relativeX + matrix.m12() * relativeY + matrix.m22() * relativeZ + matrix.m32();
					float clipW = matrix.m03() * relativeX + matrix.m13() * relativeY + matrix.m23() * relativeZ + matrix.m33();
					if (Math.abs(clipW) < 1.0e-5f) {
						continue;
					}
					float invW = 1.0f / clipW;
					float ndcX = clipX * invW;
					float ndcY = clipY * invW;
					float ndcZ = clipZ * invW;
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
			float depthRange = i < this.activeCascadeCount ? this.cascadeDepthRanges[i] : 0.0f;
			putVec4(this.cascadeTexelWorldSizes[i], i == 0 ? 0.0f : this.cascadeEnds[i - 1], this.cascadeEnds[i], depthRange, this.shadowUpload);
		}
		putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0f, this.shadowUpload);
		float filterSamples = switch (ShaderRuntime.config().shadowQualityPreset()) {
			case LOW -> 1.0f;
			case MEDIUM -> 4.0f;
			case HIGH -> 9.0f;
			case ULTRA -> 16.0f;
			case OFF -> 1.0f;
		};
		putVec4(0.92f, 0.0f, 0.30f, filterSamples, this.shadowUpload);
		this.inverseViewRotation.get(this.shadowUpload);
		this.shadowUpload.rewind();
		encoder.writeToBuffer(this.shadowDataSlice, this.shadowUpload);
	}

	public GpuBufferSlice shadowDataSlice() {
		return this.shadowDataSlice;
	}

	public boolean resourcesReady() {
		if (this.shadowDataSlice == null) {
			return false;
		}
		for (RenderTarget target : this.targets) {
			if (target == null || target.getDepthTextureView() == null || target.getColorTextureView() == null) {
				return false;
			}
		}
		for (int cascade = 0; cascade < ENTITY_CASCADE_COUNT; cascade++) {
			RenderTarget target = this.entityTargets[cascade];
			if (target == null || target.getDepthTextureView() == null || target.getColorTextureView() == null) {
				return false;
			}
		}
		return true;
	}

	public void clearCascade(CommandEncoder encoder, int cascade) {
		RenderTarget target = this.target(cascade);
		if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
			encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), 1.0);
		}
	}

	public void clearEntityCascade(CommandEncoder encoder, int cascade) {
		RenderTarget target = this.entityTarget(cascade);
		if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
			encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), 1.0);
		}
	}

	private boolean ensureResources(int[] sizes, ShaderQualityPreset quality) {
		if (this.cascadeBuffer == null) {
			this.cascadeBuffer = RenderSystem.getDevice().createBuffer(() -> "sulkan:cascade_shadow_matrix", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, CASCADE_UBO_BYTES);
			this.shadowDataBuffer = RenderSystem.getDevice().createBuffer(() -> "sulkan:directional_shadow_data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SHADOW_DATA_BYTES);
			this.cascadeSlice = this.cascadeBuffer.slice();
			this.shadowDataSlice = this.shadowDataBuffer.slice();
		}
		boolean sameSizes = this.targets[0] != null;
		for (int i = 0; i < CASCADE_COUNT; i++) {
			sameSizes &= this.targetSizes[i] == sizes[i];
			if (i < ENTITY_CASCADE_COUNT) {
				sameSizes &= this.entityTargetSizes[i] == entityTargetSize(quality, sizes, i);
			}
		}
		if (sameSizes) {
			return false;
		}
		this.destroyTargets();
		for (int i = 0; i < CASCADE_COUNT; i++) {
			this.targetSizes[i] = sizes[i];
			RenderTargetDescriptor descriptor = new RenderTargetDescriptor(sizes[i], sizes[i], true, CLEAR, GpuFormat.R8_UNORM);
			this.targets[i] = descriptor.allocate();
			descriptor.prepare(this.targets[i]);
			if (i < ENTITY_CASCADE_COUNT) {
				int entitySize = entityTargetSize(quality, sizes, i);
				this.entityTargetSizes[i] = entitySize;
				RenderTargetDescriptor entityDescriptor = new RenderTargetDescriptor(entitySize, entitySize, true, CLEAR, GpuFormat.R8_UNORM);
				this.entityTargets[i] = entityDescriptor.allocate();
				entityDescriptor.prepare(this.entityTargets[i]);
			}
		}
		return true;
	}

	private boolean shouldUpdateCascade(int cascade, boolean force) {
		if (force
				|| !this.cascadeInitialized[cascade]
				|| this.renderedTerrainRevision[cascade] != SodiumShadowTerrainRenderer.terrainRevision()) {
			return true;
		}
		int interval = CASCADE_UPDATE_INTERVALS[cascade];
		if (this.frameSerial - this.renderedFrameSerial[cascade] >= interval) {
			return true;
		}
		double dx = this.cameraX - this.renderedCameraX[cascade];
		double dy = this.cameraY - this.renderedCameraY[cascade];
		double dz = this.cameraZ - this.renderedCameraZ[cascade];
		double movementLimit = CASCADE_MOVEMENT_LIMITS[cascade];
		if (dx * dx + dy * dy + dz * dz >= movementLimit * movementLimit) {
			return true;
		}
		return this.renderedLightDirections[cascade].dot(this.lightDirection) < 0.9998f
				|| this.renderedCameraForwards[cascade].dot(this.cameraForward) < 0.9659f;
	}

	private void fitCascadeMatrix(int cascade, Matrix4f inverseProjection, float start, float end, Matrix4f destination) {
		this.buildFrustumSlice(inverseProjection, start, end);
		Vector3f center = new Vector3f();
		for (Vector3f corner : this.frustumCorners) {
			center.add(corner);
		}
		center.div(this.frustumCorners.length);

		float radius = 0.0f;
		for (Vector3f corner : this.frustumCorners) {
			radius = Math.max(radius, center.distance(corner));
		}
		radius += Math.max(2.0f, (end - start) * 0.03f);
		radius = (float) Math.ceil(radius * 16.0f) / 16.0f;

		Vector3f up = Math.abs(this.lightDirection.y) > 0.88f ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
		Vector3f lightRight = new Vector3f(up).cross(this.lightDirection).normalize();
		Vector3f lightUp = new Vector3f(this.lightDirection).cross(lightRight).normalize();

		float centerX = lightRight.dot(center);
		float centerY = lightUp.dot(center);
		float texelSize = (radius * 2.0f) / Math.max(1, this.targetSizes[cascade]);
		this.cascadeTexelWorldSizes[cascade] = texelSize;
		centerX = Math.round(centerX / texelSize) * texelSize;
		centerY = Math.round(centerY / texelSize) * texelSize;

		float depthPadding = Math.max(32.0f, Math.min(128.0f, end * 0.35f));
		// The first cascades contain nearby receivers, but their casters can be much
		// farther away along the direction to the sun. Extrude only the light-facing
		// side of those volumes across the loaded shadow range so long mountain and
		// structure shadows remain present when a near cascade becomes authoritative.
		float lightwardPadding = cascade < 2 ? Math.max(depthPadding, this.lastCoverage) : depthPadding;
		float lightwardReach = radius + lightwardPadding;
		float oppositeReach = radius + depthPadding;
		float shadowDepth = lightwardReach + oppositeReach;
		this.cascadeDepthRanges[cascade] = shadowDepth;
		Vector3f stableCenter = new Vector3f(center)
				.add(new Vector3f(lightRight).mul(centerX - lightRight.dot(center)))
				.add(new Vector3f(lightUp).mul(centerY - lightUp.dot(center)));
		Vector3f lightPos = new Vector3f(stableCenter).add(
				this.lightDirection.x * lightwardReach,
				this.lightDirection.y * lightwardReach,
				this.lightDirection.z * lightwardReach);
		Matrix4f lightView = new Matrix4f().lookAt(lightPos, stableCenter, lightUp);
		destination.identity()
				.ortho(-radius, radius, -radius, radius, 0.1f, shadowDepth, true)
				.mul(lightView);
	}

	private void buildFrustumSlice(Matrix4f inverseProjection, float start, float end) {
		int index = 0;
		for (float distance : new float[] {start, end}) {
			for (int y = -1; y <= 1; y += 2) {
				for (int x = -1; x <= 1; x += 2) {
					Vector4f view = inverseProjection.transform(new Vector4f(x, y, 1.0f, 1.0f));
					view.div(view.w);
					Vector3f ray = new Vector3f(view.x, view.y, view.z).normalize().mul(distance);
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
			if (this.entityTargets[i] != null) {
				this.entityTargets[i].destroyBuffers();
				this.entityTargets[i] = null;
			}
			this.targetSizes[i] = 0;
			this.entityTargetSizes[i] = 0;
			this.cascadeTexelWorldSizes[i] = 0.0f;
			this.cascadeDepthRanges[i] = 0.0f;
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
			case ULTRA -> new int[] {6144, 2048, 2048, 1024};
			case OFF -> new int[] {1024, 1024, 1024, 1024};
		};
	}

	private static int entityTargetSize(ShaderQualityPreset quality, int[] terrainSizes, int cascade) {
		return switch (quality) {
			case LOW -> Math.max(512, terrainSizes[cascade] / 2);
			case MEDIUM -> cascade == 0 ? 1536 : 768;
			case HIGH -> cascade == 0 ? 3072 : 1024;
			case ULTRA -> cascade == 0 ? 6144 : 2048;
			case OFF -> 512;
		};
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
