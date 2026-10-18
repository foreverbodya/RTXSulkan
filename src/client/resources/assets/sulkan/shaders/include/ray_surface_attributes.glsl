// Caller supplies a readonly address directory as SulkanRayAttributeData.
// Instance customIndex is the directory word offset, not the payload offset.
// Header stride selects legacy float32/indexed or compact packed/canonical quads.
layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer SulkanRayMeshData {uint words[];};
#ifdef SULKAN_RAY_SUBTEXEL_SCALE
vec2 sulkanRayAtlasShrink(ivec2 atlasSize) {
    // Matches Sodium UniformBufferManager.update(), including device precision.
    return vec2(1.0 / 32768.0) - vec2(SULKAN_RAY_SUBTEXEL_SCALE) / vec2(atlasSize);
}
#endif
struct SulkanRaySurface {
    vec3 position;
    vec3 normal;
    vec2 uv;
    vec4 color;
    vec2 light;
    float wind;
    float occlusion;
    uint material;
    uint flags;
    uint opacity;
};
vec3 sulkanRayVertexPosition(SulkanRayMeshData mesh, uint vertex) {
    if(mesh.words[3]==28u) {
        uint word=4u+vertex*7u,high=mesh.words[word],low=mesh.words[word+1u];
        uvec3 shift=uvec3(0,10,20);
        uvec3 q=((uvec3(high)>>shift)&1023u)*1024u+((uvec3(low)>>shift)&1023u);
        precise vec3 position=vec3(q)*(1.0/32768.0)-8.0;
        return position;
    }
    uint word = 4u + vertex * 8u;
    return uintBitsToFloat(uvec3(mesh.words[word],mesh.words[word+1u],mesh.words[word+2u]));
}
SulkanRaySurface sulkanRayAttributes(uint header, uint primitive, vec2 bary, vec2 shrink) {
    SulkanRayMeshData mesh=SulkanRayMeshData(uvec2(SulkanRayAttributeData.words[header],SulkanRayAttributeData.words[header+1u]));
    bool compact=mesh.words[3]==28u;
    uint indexStart = 4u + mesh.words[0] * 8u + primitive * 3u;
    uvec3 indices;
    if(compact)indices=(primitive/2u)*4u+((primitive&1u)==0u?uvec3(0,1,2):uvec3(2,3,0));
    else indices=uvec3(mesh.words[indexStart],mesh.words[indexStart+1u],mesh.words[indexStart+2u]);
    vec3 weights = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    SulkanRaySurface result;
    result.opacity=mesh.words[2];
    result.position = vec3(0); result.uv = vec2(0); result.color = vec4(0);
    result.light = vec2(0); result.wind = 0; result.occlusion = 0;
    for (uint i = 0u; i < 3u; i++) {
        uint vertexWord=4u+indices[i]*(compact?7u:8u);
        uint word = vertexWord+(compact?2u:4u);
        uint color = mesh.words[word];
        uint uv = mesh.words[word+1u];
        uint light = mesh.words[word+2u];
        uint material = mesh.words[word+3u];
        uvec2 tex = uvec2(uv & 65535u, uv >> 16u);
        vec2 coordinate = vec2(tex & 32767u) / 32768.0 + (vec2(tex >> 15u) * 2.0 - 1.0) * shrink;
        result.position += sulkanRayVertexPosition(mesh, indices[i]) * weights[i];
        result.uv += coordinate * weights[i];
        result.color += unpackUnorm4x8(color) * weights[i];
        result.occlusion+=uintBitsToFloat(mesh.words[vertexWord+(compact?6u:3u)])*weights[i];
        result.light += min(vec2(light & 255u, (light >> 8u) & 255u) / 240.0, vec2(1)) * weights[i];
        result.wind += float(material >> 16u) / 65535.0 * weights[i];
        if (i == 0u) { result.material = material & 65535u; result.flags = (light >> 16u) & 255u; }
    }
    result.normal = normalize(cross(sulkanRayVertexPosition(mesh, indices.y) - sulkanRayVertexPosition(mesh, indices.x),
        sulkanRayVertexPosition(mesh, indices.z) - sulkanRayVertexPosition(mesh, indices.x)));
    return result;
}
