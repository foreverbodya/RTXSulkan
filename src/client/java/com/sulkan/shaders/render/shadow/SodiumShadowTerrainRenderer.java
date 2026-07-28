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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Builds a light-space terrain list instead of reusing Sodium's camera-culled list. */
public final class SodiumShadowTerrainRenderer {
	private SodiumShadowTerrainRenderer() {
	}

	public static void render(
			SodiumWorldRenderer renderer,
			DirectionalShadowRenderer shadows,
			int cascade,
			ChunkRenderMatrices matrices,
			double cameraX,
			double cameraY,
			double cameraZ,
			TerrainRenderPass pass,
			GpuSampler sampler) {
		SodiumWorldRendererAccessor rendererAccess = (SodiumWorldRendererAccessor) renderer;
		var sectionManager = rendererAccess.sulkan$renderSectionManager();
		if (sectionManager == null) {
			return;
		}

		List<ChunkRenderList> renderLists = buildRenderLists(
				((RenderSectionManagerAccessor) sectionManager).sulkan$regions().getLoadedRegions(),
				shadows,
				cascade);
		if (renderLists.isEmpty()) {
			return;
		}

		for (ChunkRenderList list : renderLists) {
			list.getRegion().clearCachedBatchFor(pass);
		}
		var uniforms = rendererAccess.sulkan$uniformBufferManager();
		uniforms.update(matrices, FogParameters.NONE);
		boolean blockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		SodiumClientMod.options().performance.useBlockFaceCulling = false;
		try {
			sectionManager.getChunkRenderer().render(
					matrices,
					new ShadowRenderLists(renderLists),
					pass,
					new CameraTransform(cameraX, cameraY, cameraZ),
					FogParameters.NONE,
					false,
					sampler,
					uniforms.getUniformBuffer(),
					uniforms.getSectionTimeInfo());
		} finally {
			SodiumClientMod.options().performance.useBlockFaceCulling = blockFaceCulling;
			// The batch cache is keyed only by the terrain pass. Never let a
			// light-space batch leak into another cascade or the main camera pass.
			for (ChunkRenderList list : renderLists) {
				list.getRegion().clearCachedBatchFor(pass);
			}
		}
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
