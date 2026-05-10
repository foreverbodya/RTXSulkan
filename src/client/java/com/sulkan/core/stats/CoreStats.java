package com.sulkan.core.stats;

import java.util.EnumMap;
import java.util.Map;

public final class CoreStats {
	private static final EnumMap<FallbackReason, Integer> FRAME_FALLBACKS = new EnumMap<>(FallbackReason.class);

	private static int frameVanillaDraws;
	private static int frameOptimizedDraws;
	private static int frameDescriptorPushes;
	private static int frameIndirectCacheHits;
	private static int frameIndirectCacheMisses;
	private static long frameIndirectUploadBytes;
	private static int frameTerrainBatchCandidates;
	private static int frameTerrainBatches;
	private static int frameTerrainDirectDraws;
	private static int frameTerrainIndirectDraws;

	private static volatile Snapshot lastSnapshot = Snapshot.empty();

	private CoreStats() {
	}

	public static void beginFrame() {
		frameVanillaDraws = 0;
		frameOptimizedDraws = 0;
		frameDescriptorPushes = 0;
		frameIndirectCacheHits = 0;
		frameIndirectCacheMisses = 0;
		frameIndirectUploadBytes = 0L;
		frameTerrainBatchCandidates = 0;
		frameTerrainBatches = 0;
		frameTerrainDirectDraws = 0;
		frameTerrainIndirectDraws = 0;
		FRAME_FALLBACKS.clear();
	}

	public static void publishFrame() {
		lastSnapshot = new Snapshot(
				frameVanillaDraws,
				frameOptimizedDraws,
				frameDescriptorPushes,
				frameIndirectCacheHits,
				frameIndirectCacheMisses,
				frameIndirectUploadBytes,
				frameTerrainBatchCandidates,
				frameTerrainBatches,
				frameTerrainDirectDraws,
				frameTerrainIndirectDraws,
				Map.copyOf(FRAME_FALLBACKS));
	}

	public static Snapshot lastSnapshot() {
		return lastSnapshot;
	}

	public static void recordVanillaDraws(int draws, int descriptorPushes) {
		frameVanillaDraws += Math.max(draws, 0);
		frameDescriptorPushes += Math.max(descriptorPushes, 0);
	}

	public static void recordOptimizedDraws(int draws, int descriptorPushes) {
		frameOptimizedDraws += Math.max(draws, 0);
		frameDescriptorPushes += Math.max(descriptorPushes, 0);
	}

	public static void recordFallback(FallbackReason reason) {
		FRAME_FALLBACKS.merge(reason, 1, Integer::sum);
	}

	public static void recordIndirectCacheHit(int bytes) {
		frameIndirectCacheHits++;
		frameIndirectUploadBytes += Math.max(bytes, 0);
	}

	public static void recordIndirectCacheMiss(int bytes) {
		frameIndirectCacheMisses++;
		frameIndirectUploadBytes += Math.max(bytes, 0);
	}

	public static void recordTerrainBatchCandidate() {
		frameTerrainBatchCandidates++;
	}

	public static void recordTerrainBatch(int draws, boolean indirect) {
		frameTerrainBatches++;
		if (indirect) {
			frameTerrainIndirectDraws += Math.max(draws, 0);
		} else {
			frameTerrainDirectDraws += Math.max(draws, 0);
		}
	}

	public enum FallbackReason {
		SMALL_BATCH,
		NO_DYNAMIC_UNIFORMS,
		MISSING_PIPELINE,
		UNSUPPORTED_CAPABILITY,
		INVALID_DRAW,
		MIXED_TERRAIN_BUFFERS,
		MISSING_CHUNK_SECTION,
		FADING_CHUNK,
		EMPTY_TERRAIN_BATCH
	}

	public record Snapshot(
			int vanillaDraws,
			int optimizedDraws,
			int descriptorPushes,
			int indirectCacheHits,
			int indirectCacheMisses,
			long indirectUploadBytes,
			int terrainBatchCandidates,
			int terrainBatches,
			int terrainDirectDraws,
			int terrainIndirectDraws,
			Map<FallbackReason, Integer> fallbackCounts) {
		public static Snapshot empty() {
			return new Snapshot(0, 0, 0, 0, 0, 0L, 0, 0, 0, 0, Map.of());
		}
	}
}
