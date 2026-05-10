package com.sulkan.core.render;

public record PreparedTerrainDraw(
		TerrainBatchKey key,
		int encodedInstance,
		int indexCount,
		int firstIndex,
		int baseVertex) {
}
