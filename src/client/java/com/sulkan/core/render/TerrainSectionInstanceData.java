package com.sulkan.core.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;

import java.util.Map;
import java.util.WeakHashMap;

public final class TerrainSectionInstanceData {
	public static final int INSTANCE_FLAG = 0x40000000;
	private static final int COORD_MASK = 0x3FF;
	private static final int X_SHIFT = 0;
	private static final int Y_SHIFT = 10;
	private static final int Z_SHIFT = 20;

	private static final Map<GpuBufferSlice, SectionInstance> SECTIONS = new WeakHashMap<>();

	private TerrainSectionInstanceData() {
	}

	public static void record(DynamicUniforms.ChunkSectionInfo[] infos, GpuBufferSlice[] slices) {
		if (infos == null || slices == null) {
			return;
		}

		int count = Math.min(infos.length, slices.length);
		for (int i = 0; i < count; i++) {
			DynamicUniforms.ChunkSectionInfo info = infos[i];
			GpuBufferSlice slice = slices[i];
			if (info == null || slice == null) {
				continue;
			}

			int sectionX = Math.floorDiv(info.x(), 16);
			int sectionY = Math.floorDiv(info.y(), 16);
			int sectionZ = Math.floorDiv(info.z(), 16);
			SECTIONS.put(slice, new SectionInstance(
					encode(sectionX, sectionY, sectionZ),
					sectionX,
					sectionY,
					sectionZ,
					info.visibility()));
		}
	}

	public static SectionInstance get(GpuBufferSlice slice) {
		return SECTIONS.get(slice);
	}

	public static void clear() {
		SECTIONS.clear();
	}

	private static int encode(int sectionX, int sectionY, int sectionZ) {
		return INSTANCE_FLAG
				| ((sectionX & COORD_MASK) << X_SHIFT)
				| ((sectionY & COORD_MASK) << Y_SHIFT)
				| ((sectionZ & COORD_MASK) << Z_SHIFT);
	}

	public record SectionInstance(int encodedInstance, int sectionX, int sectionY, int sectionZ, float visibility) {
	}
}
