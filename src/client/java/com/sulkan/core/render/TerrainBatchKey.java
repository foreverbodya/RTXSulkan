package com.sulkan.core.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;

public record TerrainBatchKey(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType) {
}
