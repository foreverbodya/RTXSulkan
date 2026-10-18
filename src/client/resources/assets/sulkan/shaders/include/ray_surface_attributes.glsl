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
#if REALTIME_RAY_LOD == 1
    vec2 uvGradientLength;
    vec2 uvMinimum;
    vec2 uvMaximum;
#endif
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
// The full surface decoder takes flat material from the first indexed vertex.
// Read exactly that word when only classification is needed: no barycentric,
// atlas-size, interpolated attributes or normal calculation participates.
// Compact quads and legacy explicit indices follow the same paths as below.
uint sulkanRayMaterial(uint header,uint primitive) {
    SulkanRayMeshData mesh=SulkanRayMeshData(uvec2(SulkanRayAttributeData.words[header],SulkanRayAttributeData.words[header+1u]));
    bool compact=mesh.words[3]==28u;
    uint first;
    if(compact)first=(primitive/2u)*4u+((primitive&1u)==0u?0u:2u);
    else first=mesh.words[4u+mesh.words[0]*8u+primitive*3u];
    uint word=4u+first*(compact?7u:8u)+(compact?5u:7u);
    return mesh.words[word]&65535u;
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
#if REALTIME_RAY_LOD == 1
    vec2 coordinates[3];
#endif
    for (uint i = 0u; i < 3u; i++) {
        uint vertexWord=4u+indices[i]*(compact?7u:8u);
        uint word = vertexWord+(compact?2u:4u);
        uint color = mesh.words[word];
        uint uv = mesh.words[word+1u];
        uint light = mesh.words[word+2u];
        uint material = mesh.words[word+3u];
        uvec2 tex = uvec2(uv & 65535u, uv >> 16u);
        vec2 coordinate = vec2(tex & 32767u) / 32768.0 + (vec2(tex >> 15u) * 2.0 - 1.0) * shrink;
#if REALTIME_RAY_LOD == 1
        coordinates[i]=coordinate;
#endif
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
#if REALTIME_RAY_LOD == 1
    vec3 e1=sulkanRayVertexPosition(mesh,indices.y)-sulkanRayVertexPosition(mesh,indices.x);
    vec3 e2=sulkanRayVertexPosition(mesh,indices.z)-sulkanRayVertexPosition(mesh,indices.x);
    float area=max(length(cross(e1,e2)),1e-10);
    vec3 a=cross(e2,result.normal)/area,b=cross(result.normal,e1)/area;
    vec2 t1=coordinates[1]-coordinates[0],t2=coordinates[2]-coordinates[0];
    result.uvGradientLength=vec2(length(t1.x*a+t2.x*b),length(t1.y*a+t2.y*b));
    result.uvMinimum=min(coordinates[0],min(coordinates[1],coordinates[2]));
    result.uvMaximum=max(coordinates[0],max(coordinates[1],coordinates[2]));
#endif
    return result;
}

// Pixel cone at the hit surface. Atlas mip count and sprite footprint cap the
// lookup; no sampling beyond a sprite's available texel footprint.
vec4 sulkanRayTexture(sampler2D atlas,SulkanRaySurface hit,vec3 position,vec3 incident){
#if REALTIME_RAY_LOD == 1
    if((hit.flags&1u)==0u)return textureLod(atlas,hit.uv,0.0);
    vec2 texels=vec2(textureSize(atlas,0));
    float cone=2.0*length(position-RayCameraPosition.xyz)/(max(abs(Projection[1][1]),.01)*ViewSizeAndInverse.y);
    cone*=exp2(RayCameraPosition.w); // Dynamic input/output ratio, zero at native.
    float footprint=max(hit.uvGradientLength.x*texels.x,hit.uvGradientLength.y*texels.y)*cone;
    footprint/=max(abs(dot(hit.normal,incident)),.15);
    vec2 extent=(hit.uvMaximum-hit.uvMinimum)*texels;
    float maximum=min(float(textureQueryLevels(atlas)-1),max(0.0,log2(max(min(extent.x,extent.y),1.0))));
    float lod=clamp(log2(max(footprint,1e-8)),0.0,maximum);
    vec2 inset=.5*exp2(ceil(lod))/texels;
    vec2 centre=(hit.uvMinimum+hit.uvMaximum)*.5;
    vec2 coordinate=clamp(hit.uv,min(hit.uvMinimum+inset,centre),max(hit.uvMaximum-inset,centre));
    return textureLod(atlas,coordinate,lod);
#else
    return textureLod(atlas,hit.uv,0.0);
#endif
}
