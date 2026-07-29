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
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

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

	public static long terrainRevision() {
		return terrainRevision;
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

		boolean spatiallyReusable = cached != null && cached.spatiallyMatches(
				cameraX,
				cameraY,
				cameraZ,
				lightDirection,
				cameraForward,
				shadows.cascadeEnd(cascade),
				PLAN_MOVEMENT_LIMITS[cascade]);
		PlanContents contents = buildRenderLists(regions, shadows, cascade, cached, spatiallyReusable);
		CascadePlan next = new CascadePlan(
				contents.lists,
				new ShadowRenderLists(contents.lists),
				contents.regions,
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

	private static PlanContents buildRenderLists(
			Iterable<RenderRegion> regions,
			DirectionalShadowRenderer shadows,
			int cascade,
			CascadePlan previous,
			boolean spatiallyReusable) {
		List<ChunkRenderList> result = new ArrayList<>();
		Map<RenderRegion, RegionPlan> nextRegions = new IdentityHashMap<>();
		for (RenderRegion region : regions) {
			long revision = ((ShadowRegionState) region).sulkan$shadowRevision();
			RegionPlan priorRegion = previous == null ? null : previous.regions.get(region);
			RegionPlan nextRegion;
			if (spatiallyReusable && priorRegion != null && priorRegion.revision == revision) {
				nextRegion = priorRegion;
			} else {
				nextRegion = buildRegionPlan(region, shadows, cascade, revision);
			}
			nextRegions.put(region, nextRegion);
			if (!nextRegion.sections.isEmpty()) {
				result.add(nextRegion.list);
			}
		}
		return new PlanContents(List.copyOf(result), nextRegions);
	}

	private static RegionPlan buildRegionPlan(RenderRegion region, DirectionalShadowRenderer shadows, int cascade, long revision) {
		ChunkRenderList list = new ChunkRenderList(region);
		BitSet sections = new BitSet(RenderRegion.REGION_SIZE);
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
				sections.set(index);
			}
		}
		return new RegionPlan(list, sections, revision);
	}

	private static void clearChangedPlanBatches(CascadePlan previous, CascadePlan next, int cascade) {
		if (previous == null) {
			return;
		}
		for (Map.Entry<RenderRegion, RegionPlan> entry : previous.regions.entrySet()) {
			RegionPlan replacement = next.regions.get(entry.getKey());
			RegionPlan prior = entry.getValue();
			if (replacement == null
					|| prior.revision != replacement.revision
					|| !prior.sections.equals(replacement.sections)) {
				clearRegionBatches(entry.getKey(), cascade);
			}
		}
		for (Map.Entry<RenderRegion, RegionPlan> entry : next.regions.entrySet()) {
			if (!previous.regions.containsKey(entry.getKey()) && !entry.getValue().sections.isEmpty()) {
				clearRegionBatches(entry.getKey(), cascade);
			}
		}
	}

	private static void clearRegionBatches(RenderRegion region, int cascade) {
		region.clearCachedBatchFor(SodiumShadowTerrainPasses.solid(cascade));
		region.clearCachedBatchFor(SodiumShadowTerrainPasses.cutout(cascade));
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
			Map<RenderRegion, RegionPlan> regions,
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
			return this.revision == currentRevision && this.spatiallyMatches(
					currentCameraX, currentCameraY, currentCameraZ,
					currentLightDirection, currentCameraForward, currentCascadeEnd, movementLimit);
		}

		private boolean spatiallyMatches(
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
			return dx * dx + dy * dy + dz * dz <= movementLimit * movementLimit
					&& this.lightDirection.dot(currentLightDirection) >= PLAN_DIRECTION_DOT
					&& this.cameraForward.dot(currentCameraForward) >= PLAN_DIRECTION_DOT
					&& Math.abs(this.cascadeEnd - currentCascadeEnd) < 0.5f;
		}
	}

	private record RegionPlan(ChunkRenderList list, BitSet sections, long revision) {
	}

	private record PlanContents(List<ChunkRenderList> lists, Map<RenderRegion, RegionPlan> regions) {
	}

	private record ShadowRenderLists(List<ChunkRenderList> lists) implements ChunkRenderListIterable {
		@Override
		public Iterator<ChunkRenderList> iterator(boolean reverse) {
			if (!reverse) {
				return this.lists.iterator();
			}
			ListIterator<ChunkRenderList> iterator = this.lists.listIterator(this.lists.size());
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					return iterator.hasPrevious();
				}

				@Override
				public ChunkRenderList next() {
					return iterator.previous();
				}
			};
		}
	}
}
