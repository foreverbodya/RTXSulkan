#version 460 core

#define SULKAN_GLOBALS_BINDING 10
#moj_import <sulkan:sodium_globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_vertex.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 11) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_FragDistance;
layout(location = 3) out float v_FadeFactor;
layout(location = 4) out vec3 v_ReceiverPos;
layout(location = 5) out float v_BlockLight;
layout(location = 6) out float v_SkyLight;

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
	int chunkFade = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + _draw_id)).r;

	gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
	v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
	v_TexCoord = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
	v_FragDistance = getFragDistance(position);
	v_FadeFactor = chunkFade < 0 ? 1.0 : clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
	v_ReceiverPos = position;
	v_BlockLight = clamp(_vert_tex_light_coord.x * (16.0 / 15.0), 0.0, 1.0);
	v_SkyLight = clamp(_vert_tex_light_coord.y * (16.0 / 15.0), 0.0, 1.0);
}
