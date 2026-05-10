package com.sulkan.core.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class TerrainRegion {
	public static final int REGION_WIDTH = 8;
	public static final int REGION_HEIGHT = 4;
	public static final int REGION_LENGTH = 8;
	public static final int REGION_SIZE = REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH;

	private static final int REGION_WIDTH_SHIFT = 3;
	private static final int REGION_LENGTH_SHIFT = 3;

	private final int regionX;
	private final int regionY;
	private final int regionZ;
	private final LayerState[] layers = new LayerState[TerrainLayer.values().length];
	private long lastTouchedFrame = -1L;

	public TerrainRegion(int regionX, int regionY, int regionZ) {
		this.regionX = regionX;
		this.regionY = regionY;
		this.regionZ = regionZ;
		Arrays.setAll(this.layers, ignored -> new LayerState());
	}

	public static long key(int regionX, int regionY, int regionZ) {
		return SectionPos.asLong(regionX, regionY, regionZ);
	}

	public static int regionX(int sectionX) {
		return Math.floorDiv(sectionX, REGION_WIDTH);
	}

	public static int regionY(int sectionY) {
		return Math.floorDiv(sectionY, REGION_HEIGHT);
	}

	public static int regionZ(int sectionZ) {
		return Math.floorDiv(sectionZ, REGION_LENGTH);
	}

	public static int localSectionIndex(int sectionX, int sectionY, int sectionZ) {
		int localX = Math.floorMod(sectionX, REGION_WIDTH);
		int localY = Math.floorMod(sectionY, REGION_HEIGHT);
		int localZ = Math.floorMod(sectionZ, REGION_LENGTH);
		return localX | (localZ << REGION_WIDTH_SHIFT) | (localY << (REGION_WIDTH_SHIFT + REGION_LENGTH_SHIFT));
	}

	public boolean markTouched(long frameSerial) {
		if (this.lastTouchedFrame == frameSerial) {
			return false;
		}

		this.lastTouchedFrame = frameSerial;
		return true;
	}

	public long lastTouchedFrame() {
		return this.lastTouchedFrame;
	}

	public RecordResult recordDraw(TerrainLayer layer, int localSectionIndex, DrawMetadata metadata, long frameSerial) {
		return this.layers[layer.ordinal()].recordDraw(localSectionIndex, metadata, frameSerial);
	}

	public long batchVersion(TerrainLayer layer) {
		return this.layers[layer.ordinal()].batchVersion;
	}

	public int sectionCount(TerrainLayer layer) {
		return this.layers[layer.ordinal()].sectionCount;
	}

	public record DrawMetadata(
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			int indexCount,
			int firstIndex,
			int baseVertex,
			int baseInstance) {
	}

	public record RecordResult(
			boolean layerFirstTouchedThisFrame,
			boolean sectionFirstVisibleThisFrame,
			boolean metadataChanged,
			boolean layerFirstDirtyThisFrame) {
	}

	private static final class SectionDrawMetadata {
		private @Nullable DrawMetadata metadata;
		private long lastVisibleFrame = -1L;

		private SectionDrawMetadata(DrawMetadata metadata) {
			this.metadata = metadata;
		}
	}

	private static final class LayerState {
		private final SectionDrawMetadata[] sections = new SectionDrawMetadata[REGION_SIZE];
		private int sectionCount;
		private long batchVersion;
		private long lastTouchedFrame = -1L;
		private long lastDirtyFrame = -1L;

		private RecordResult recordDraw(int localSectionIndex, DrawMetadata metadata, long frameSerial) {
			boolean layerFirstTouched = this.lastTouchedFrame != frameSerial;
			if (layerFirstTouched) {
				this.lastTouchedFrame = frameSerial;
			}

			SectionDrawMetadata section = this.sections[localSectionIndex];
			boolean metadataChanged = false;
			if (section == null) {
				section = new SectionDrawMetadata(metadata);
				this.sections[localSectionIndex] = section;
				this.sectionCount++;
				this.batchVersion++;
				metadataChanged = true;
			} else if (!metadata.equals(section.metadata)) {
				section.metadata = metadata;
				this.batchVersion++;
				metadataChanged = true;
			}

			boolean sectionFirstVisible = section.lastVisibleFrame != frameSerial;
			if (sectionFirstVisible) {
				section.lastVisibleFrame = frameSerial;
			}

			boolean layerFirstDirty = false;
			if (metadataChanged && this.lastDirtyFrame != frameSerial) {
				this.lastDirtyFrame = frameSerial;
				layerFirstDirty = true;
			}

			return new RecordResult(layerFirstTouched, sectionFirstVisible, metadataChanged, layerFirstDirty);
		}
	}
}
