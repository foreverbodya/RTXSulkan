package com.sulkan.core.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class TerrainRegionManager {
	private static final int PRUNE_INTERVAL_FRAMES = 300;
	private static final long STALE_REGION_FRAMES = 1200L;
	private static final Map<Long, TerrainRegion> REGIONS = new HashMap<>();

	private static long frameSerial;
	private static int frameTouchedRegions;
	private static int frameLayerBatches;
	private static int frameSubmittedRegionBatches;
	private static int frameVisibleSections;
	private static int frameSubmittedCommands;
	private static int frameDirtySections;
	private static int frameDirtyLayerBatches;

	private static volatile Stats lastStats = Stats.empty();

	private TerrainRegionManager() {
	}

	public static void beginFrame() {
		frameSerial++;
		frameTouchedRegions = 0;
		frameLayerBatches = 0;
		frameSubmittedRegionBatches = 0;
		frameVisibleSections = 0;
		frameSubmittedCommands = 0;
		frameDirtySections = 0;
		frameDirtyLayerBatches = 0;

		if (frameSerial % PRUNE_INTERVAL_FRAMES == 0L) {
			pruneStaleRegions();
		}
	}

	public static void publishFrameStats() {
		lastStats = new Stats(
				REGIONS.size(),
				frameTouchedRegions,
				frameLayerBatches,
				frameSubmittedRegionBatches,
				frameVisibleSections,
				frameSubmittedCommands,
				frameDirtySections,
				frameDirtyLayerBatches);
	}

	public static Stats lastStats() {
		return lastStats;
	}

	public static RecordedDraw recordDraw(
			TerrainLayer layer,
			TerrainSectionInstanceData.SectionInstance section,
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			int indexCount,
			int firstIndex,
			int baseVertex) {
		int regionX = TerrainRegion.regionX(section.sectionX());
		int regionY = TerrainRegion.regionY(section.sectionY());
		int regionZ = TerrainRegion.regionZ(section.sectionZ());
		long regionKey = TerrainRegion.key(regionX, regionY, regionZ);
		TerrainRegion region = REGIONS.computeIfAbsent(regionKey, ignored -> new TerrainRegion(regionX, regionY, regionZ));

		if (region.markTouched(frameSerial)) {
			frameTouchedRegions++;
		}

		TerrainRegion.DrawMetadata metadata = new TerrainRegion.DrawMetadata(
				vertexBuffer,
				indexBuffer,
				indexType,
				indexCount,
				firstIndex,
				baseVertex,
				section.encodedInstance());
		TerrainRegion.RecordResult result = region.recordDraw(
				layer,
				TerrainRegion.localSectionIndex(section.sectionX(), section.sectionY(), section.sectionZ()),
				metadata,
				frameSerial);
		if (result.layerFirstTouchedThisFrame()) {
			frameLayerBatches++;
		}
		if (result.sectionFirstVisibleThisFrame()) {
			frameVisibleSections++;
		}
		if (result.metadataChanged()) {
			frameDirtySections++;
		}
		if (result.layerFirstDirtyThisFrame()) {
			frameDirtyLayerBatches++;
		}

		return new RecordedDraw(
				vertexBuffer,
				indexBuffer,
				indexType,
				indexCount,
				firstIndex,
				baseVertex,
				section.encodedInstance());
	}

	public static void recordSubmittedRegionBatch() {
		frameSubmittedRegionBatches++;
	}

	public static void recordSubmittedCommands(int commandCount) {
		frameSubmittedCommands += Math.max(commandCount, 0);
	}

	public static void close() {
		clear();
		frameSerial = 0L;
	}

	public static void clear() {
		REGIONS.clear();
		frameTouchedRegions = 0;
		frameLayerBatches = 0;
		frameSubmittedRegionBatches = 0;
		frameVisibleSections = 0;
		frameSubmittedCommands = 0;
		frameDirtySections = 0;
		frameDirtyLayerBatches = 0;
		lastStats = Stats.empty();
	}

	private static void pruneStaleRegions() {
		Iterator<TerrainRegion> iterator = REGIONS.values().iterator();
		while (iterator.hasNext()) {
			TerrainRegion region = iterator.next();
			if (frameSerial - region.lastTouchedFrame() > STALE_REGION_FRAMES) {
				iterator.remove();
			}
		}
	}

	public record RecordedDraw(
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			int indexCount,
			int firstIndex,
			int baseVertex,
			int encodedInstance) {
	}

	public record Stats(
			int totalRegions,
			int touchedRegions,
			int layerBatches,
			int submittedRegionBatches,
			int visibleSections,
			int submittedCommands,
			int dirtySections,
			int dirtyLayerBatches) {
		public static Stats empty() {
			return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
		}
	}
}
