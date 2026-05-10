#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>

const uint SULKAN_INSTANCE_SECTION_FLAG = 0x40000000u;
const uint SULKAN_INSTANCE_COORD_MASK = 1023u;
const int SULKAN_INSTANCE_COORD_HALF = 512;

bool sulkan_usesEncodedChunkSection() {
	return (uint(gl_InstanceIndex) & SULKAN_INSTANCE_SECTION_FLAG) != 0u;
}

int sulkan_floorDiv16(int value) {
	return value >= 0 ? value / 16 : -((-value + 15) / 16);
}

int sulkan_decodeSectionCoord(uint packed, int shift, int cameraSection) {
	int encoded = int((packed >> uint(shift)) & SULKAN_INSTANCE_COORD_MASK);
	int cameraLow = cameraSection & int(SULKAN_INSTANCE_COORD_MASK);
	int delta = (encoded - cameraLow + SULKAN_INSTANCE_COORD_HALF) & int(SULKAN_INSTANCE_COORD_MASK);
	return cameraSection + delta - SULKAN_INSTANCE_COORD_HALF;
}

ivec3 sulkan_decodeChunkPosition() {
	uint packed = uint(gl_InstanceIndex);
	ivec3 cameraSection = ivec3(
			sulkan_floorDiv16(CameraBlockPos.x),
			sulkan_floorDiv16(CameraBlockPos.y),
			sulkan_floorDiv16(CameraBlockPos.z));
	return ivec3(
			sulkan_decodeSectionCoord(packed, 0, cameraSection.x),
			sulkan_decodeSectionCoord(packed, 10, cameraSection.y),
			sulkan_decodeSectionCoord(packed, 20, cameraSection.z)) * 16;
}

ivec3 sulkan_chunkPosition() {
	return sulkan_usesEncodedChunkSection() ? sulkan_decodeChunkPosition() : ChunkPosition;
}

float sulkan_chunkVisibility() {
	return sulkan_usesEncodedChunkSection() ? 1.0 : ChunkVisibility;
}

ivec2 sulkan_textureSize() {
	return TextureSize;
}
