package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.textures.GpuSampler;
import com.sulkan.shaders.mixin.sodium.RenderSectionManagerAccessor;
import com.sulkan.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds and caches light-space terrain lists independently from Sodium's camera list. */
public final class SodiumShadowTerrainRenderer {
	private static final int CASCADE_COUNT = 4;
	private static final double[] PLAN_MOVEMENT_LIMITS = {4.0, 8.0, 16.0, 32.0};
	private static final float PLAN_DIRECTION_DOT = 0.9925f;
	private static final CascadePlan[] CACHED_PLANS = new CascadePlan[CASCADE_COUNT];
	private static long terrainRevision;

	private SodiumShadowTerrainRenderer() {
	}

	public static void renderCascade(
			SodiumWorldRenderer renderer,
			DirectionalShadowRenderer shadows,
			int cascade,
			ChunkRenderMatrices matrices,
			double cameraX,
			double cameraY,
			double cameraZ,
			GpuSampler sampler) {
		SodiumWorldRendererAccessor rendererAccess = (SodiumWorldRendererAccessor) renderer;
		var sectionManager = rendererAccess.sulkan$renderSectionManager();
		if (sectionManager == null) {
			return;
		}

		Iterable<RenderRegion> regions = ((RenderSectionManagerAccessor) sectionManager)
				.sulkan$regions()
				.getLoadedRegions();
		CascadePlan plan = planFor(regions, shadows, cascade, cameraX, cameraY, cameraZ);
		if (plan.lists.isEmpty()) {
			return;
		}

		var uniforms = rendererAccess.sulkan$uniformBufferManager();
		uniforms.update(matrices, FogParameters.NONE);
		boolean blockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		SodiumClientMod.options().performance.useBlockFaceCulling = false;
		try {
			renderPass(sectionManager.getChunkRenderer(), matrices, plan.renderLists,
					SodiumShadowTerrainPasses.solid(cascade), cameraX, cameraY, cameraZ, sampler,
					uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
			renderPass(sectionManager.getChunkRenderer(), matrices, plan.renderLists,
					SodiumShadowTerrainPasses.cutout(cascade), cameraX, cameraY, cameraZ, sampler,
					uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
		} finally {
			SodiumClientMod.options().performance.useBlockFaceCulling = blockFaceCulling;
		}
	}

	public static void markTerrainDirty() {
		terrainRevision++;
	}

	public static void close() {
		for (int cascade = 0; cascade < CACHED_PLANS.length; cascade++) {
			clearPlanBatches(CACHED_PLANS[cascade], cascade);
			CACHED_PLANS[cascade] = null;
		}
		terrainRevision++;
	}

	private static CascadePlan planFor(
			Iterable<RenderRegion> regions,
			DirectionalShadowRenderer shadows,
			int cascade,
			double cameraX,
			double cameraY,
			double cameraZ) {
		CascadePlan cached = CACHED_PLANS[cascade];
		Vector3f lightDirection = shadows.lightDirection(new Vector3f());
		Vector3f cameraForward = shadows.cameraForward(new Vector3f());
		if (cached != null && cached.matches(
				terrainRevision,
				cameraX,
				cameraY,
				cameraZ,
				lightDirection,
				cameraForward,
				shadows.cascadeEnd(cascade),
				PLAN_MOVEMENT_LIMITS[cascade])) {
			return cached;
		}

		List<ChunkRenderList> lists = buildRenderLists(regions, shadows, cascade);
		CascadePlan next = new CascadePlan(
				List.copyOf(lists),
				new ShadowRenderLists(List.copyOf(lists)),
				terrainRevision,
				cameraX,
				cameraY,
				cameraZ,
				lightDirection,
				cameraForward,
				shadows.cascadeEnd(cascade));
		clearChangedPlanBatches(cached, next, cascade);
		CACHED_PLANS[cascade] = next;
		return next;
	}

	private static void renderPass(
			net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer renderer,
			ChunkRenderMatrices matrices,
			ChunkRenderListIterable renderLists,
			TerrainRenderPass pass,
			double cameraX,
			double cameraY,
			double cameraZ,
			GpuSampler sampler,
			com.mojang.blaze3d.buffers.GpuBufferSlice uniformBuffer,
			com.mojang.blaze3d.buffers.GpuBuffer sectionTimeInfo) {
		renderer.render(
				matrices,
				renderLists,
				pass,
				new CameraTransform(cameraX, cameraY, cameraZ),
				FogParameters.NONE,
				false,
				sampler,
				uniformBuffer,
				sectionTimeInfo);
	}

	private static List<ChunkRenderList> buildRenderLists(
			Iterable<RenderRegion> regions,
			DirectionalShadowRenderer shadows,
			int cascade) {
		List<ChunkRenderList> result = new ArrayList<>();
		for (RenderRegion region : regions) {
			ChunkRenderList list = new ChunkRenderList(region);
			list.reset(0);
			for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
				int flags = region.getSectionFlags(index);
				if ((flags & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) {
					continue;
				}
				int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(index);
				int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(index);
				int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
				if (shadows.sectionIntersectsCascade(cascade, sectionX << 4, sectionY << 4, sectionZ << 4)) {
					list.add(index);
				}
			}
			if (list.getSectionsWithGeometryCount() > 0) {
				result.add(list);
			}
		}
		return result;
	}

	private static void clearChangedPlanBatches(CascadePlan previous, CascadePlan next, int cascade) {
		Set<RenderRegion> regions = new LinkedHashSet<>();
		if (previous != null) {
			for (ChunkRenderList list : previous.lists) {
				regions.add(list.getRegion());
			}
		}
		for (ChunkRenderList list : next.lists) {
			regions.add(list.getRegion());
		}
		for (RenderRegion region : regions) {
			region.clearCachedBatchFor(SodiumShadowTerrainPasses.solid(cascade));
			region.clearCachedBatchFor(SodiumShadowTerrainPasses.cutout(cascade));
		}
	}

	private static void clearPlanBatches(CascadePlan plan, int cascade) {
		if (plan == null) {
			return;
		}
		for (ChunkRenderList list : plan.lists) {
			RenderRegion region = list.getRegion();
			region.clearCachedBatchFor(SodiumShadowTerrainPasses.solid(cascade));
			region.clearCachedBatchFor(SodiumShadowTerrainPasses.cutout(cascade));
		}
	}

	private record CascadePlan(
			List<ChunkRenderList> lists,
			ShadowRenderLists renderLists,
			long revision,
			double cameraX,
			double cameraY,
			double cameraZ,
			Vector3f lightDirection,
			Vector3f cameraForward,
			float cascadeEnd) {
		private boolean matches(
				long currentRevision,
				double currentCameraX,
				double currentCameraY,
				double currentCameraZ,
				Vector3f currentLightDirection,
				Vector3f currentCameraForward,
				float currentCascadeEnd,
				double movementLimit) {
			double dx = currentCameraX - this.cameraX;
			double dy = currentCameraY - this.cameraY;
			double dz = currentCameraZ - this.cameraZ;
			return this.revision == currentRevision
					&& dx * dx + dy * dy + dz * dz <= movementLimit * movementLimit
					&& this.lightDirection.dot(currentLightDirection) >= PLAN_DIRECTION_DOT
					&& this.cameraForward.dot(currentCameraForward) >= PLAN_DIRECTION_DOT
					&& Math.abs(this.cascadeEnd - currentCascadeEnd) < 0.5f;
		}
	}

	private record ShadowRenderLists(List<ChunkRenderList> lists) implements ChunkRenderListIterable {
		@Override
		public Iterator<ChunkRenderList> iterator(boolean reverse) {
			if (!reverse) {
				return this.lists.iterator();
			}
			List<ChunkRenderList> reversed = new ArrayList<>(this.lists);
			Collections.reverse(reversed);
			return reversed.iterator();
		}
	}
}
