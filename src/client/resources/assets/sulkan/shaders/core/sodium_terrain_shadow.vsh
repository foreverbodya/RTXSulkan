#version 460 core

#define SULKAN_GLOBALS_BINDING 2
#moj_import <sulkan:sodium_globals.glsl>
#moj_import <sodium:chunk_vertex.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 3) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) out vec2 v_TexCoord;

#ifdef VULKAN
layout(push_constant) uniform PC {
	vec3 u_RegionOffset;
	int u_CurrentTime;
	uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

uvec3 sulkanRelativeChunkCoord(uint drawId) {
	return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

void main() {
	_vert_init();
	vec3 translation = u_RegionOffset + sulkanRelativeChunkCoord(_draw_id) * vec3(16.0);
	vec3 position = _vert_position + translation;
	gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
	v_TexCoord = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
}
