package com.sulkan.core.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class TerrainDrawBatch implements AutoCloseable {
	public final GpuBuffer vertexBuffer;
	public final GpuBuffer indexBuffer;
	public final IndexType indexType;
	public final GpuBufferSlice chunkSectionSlice;
	public final ByteBuffer commands;
	public final int drawCount;
	private final boolean ownsCommands;

	public TerrainDrawBatch(
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			GpuBufferSlice chunkSectionSlice,
			ByteBuffer commands,
			int drawCount) {
		this(vertexBuffer, indexBuffer, indexType, chunkSectionSlice, commands, drawCount, false);
	}

	public TerrainDrawBatch(
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			GpuBufferSlice chunkSectionSlice,
			ByteBuffer commands,
			int drawCount,
			boolean ownsCommands) {
		this.vertexBuffer = vertexBuffer;
		this.indexBuffer = indexBuffer;
		this.indexType = indexType;
		this.chunkSectionSlice = chunkSectionSlice;
		this.commands = commands;
		this.drawCount = drawCount;
		this.ownsCommands = ownsCommands;
	}

	@Override
	public void close() {
		if (this.ownsCommands) {
			MemoryUtil.memFree(this.commands);
		}
	}
}
