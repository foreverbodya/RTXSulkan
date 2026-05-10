package com.sulkan.core.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import java.nio.ByteBuffer;

public final class TerrainBatchBuilder {
	public final GpuBufferSlice firstChunkSectionSlice;
	public int commandCount;
	public ByteBuffer commands;

	public TerrainBatchBuilder(GpuBufferSlice firstChunkSectionSlice) {
		this.firstChunkSectionSlice = firstChunkSectionSlice;
	}
}
