package com.sulkan.shaders.render.shadow;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Dedicated pass identities keep light-space command batches separate from
 * Sodium's main-camera batches while sharing the same uploaded mesh storage.
 */
public final class SodiumShadowTerrainPasses {
	private static final int CASCADE_COUNT = 4;
	private static final TerrainRenderPass[] SOLID = new TerrainRenderPass[CASCADE_COUNT];
	private static final TerrainRenderPass[] CUTOUT = new TerrainRenderPass[CASCADE_COUNT];
	private static final TerrainRenderPass[] EMPTY = new TerrainRenderPass[0];

	static {
		for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
			SOLID[cascade] = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);
			CUTOUT[cascade] = new TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true);
		}
	}

	private SodiumShadowTerrainPasses() {
	}

	public static TerrainRenderPass solid(int cascade) {
		return SOLID[cascade];
	}

	public static TerrainRenderPass cutout(int cascade) {
		return CUTOUT[cascade];
	}

	public static TerrainRenderPass source(TerrainRenderPass pass) {
		for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
			if (pass == SOLID[cascade]) {
				return DefaultTerrainRenderPasses.SOLID;
			}
			if (pass == CUTOUT[cascade]) {
				return DefaultTerrainRenderPasses.CUTOUT;
			}
		}
		return pass;
	}

	public static TerrainRenderPass[] passesFor(TerrainRenderPass source) {
		if (source == DefaultTerrainRenderPasses.SOLID) {
			return SOLID;
		}
		if (source == DefaultTerrainRenderPasses.CUTOUT) {
			return CUTOUT;
		}
		return EMPTY;
	}
}
