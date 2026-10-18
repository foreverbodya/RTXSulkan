#ifndef SULKAN_CINEMATIC_CLOUD_CELLULAR
#define SULKAN_CINEMATIC_CLOUD_CELLULAR
// 128^3 RGBA8 volume in 16x8 slices, sampled at voxel centers. Explicit fetches
// preserve 3D periodic interpolation at slice/tile edges in the 2D texture API.
vec4 cinematicCloudCellularScale(sampler2D layoutTexture,sampler2D noiseTexture,vec3 p,float scale) {
    vec2 worldPeriod=cinematicCloudPeriod(layoutTexture);
    vec2 repeats=max(ceil(worldPeriod/scale),vec2(1));
    precise vec3 cellSize=vec3(worldPeriod.x/(repeats.x*128.0),scale/128.0,worldPeriod.y/(repeats.y*128.0));
    precise vec3 positionCell=floor(p/cellSize),positionLocal=p-positionCell*cellSize;
    precise vec3 phase=vec3(cinematicCloudPhase(),-cinematicCloudBottom(),3.960000038),phaseCell=floor(phase/cellSize);
    // Preserve split-lattice evaluation instead of combining a small phase with
    // a large position before subtraction. Translated-input probes retain it.
    precise vec3 local=positionLocal+(phase-phaseCell*cellSize)-cellSize*0.5,carry=floor(local/cellSize);
    ivec3 cell=ivec3(positionCell+phaseCell+carry);precise vec3 w=clamp((local-carry*cellSize)/cellSize,vec3(0),vec3(1));
    // Power-of-two periodic lattice: low seven bits are the nonnegative
    // modulo even for negative cells. Wrap once per endpoint, not per corner.
    // Keep the original z/y/x accumulation and FP32 multiply order; these are
    // still eight explicit voxel fetches, with no hardware filter quantization.
    ivec3 a=cell&ivec3(127),b=(a+ivec3(1))&ivec3(127);
    ivec2 slice0=ivec2((a.z&15)*128,(a.z>>4)*128);
    ivec2 slice1=ivec2((b.z&15)*128,(b.z>>4)*128);
    vec3 v=1.0-w;
    precise vec4 result=vec4(0);
#if defined(SULKAN_CLOUD_NOISE_EXACT_3D) && SULKAN_CLOUD_NOISE_EXACT_3D == 1
    // Same eight RGBA8 texel codes, same explicit weights and accumulation order.
    // Only texture storage/addressing changes; hardware interpolation is unused.
    result+=texelFetch(CloudExactNoise,a+ivec3(1),0)*v.x*v.y*v.z;
    result+=texelFetch(CloudExactNoise,ivec3(b.x,a.y,a.z)+ivec3(1),0)*w.x*v.y*v.z;
    result+=texelFetch(CloudExactNoise,ivec3(a.x,b.y,a.z)+ivec3(1),0)*v.x*w.y*v.z;
    result+=texelFetch(CloudExactNoise,ivec3(b.x,b.y,a.z)+ivec3(1),0)*w.x*w.y*v.z;
    result+=texelFetch(CloudExactNoise,ivec3(a.x,a.y,b.z)+ivec3(1),0)*v.x*v.y*w.z;
    result+=texelFetch(CloudExactNoise,ivec3(b.x,a.y,b.z)+ivec3(1),0)*w.x*v.y*w.z;
    result+=texelFetch(CloudExactNoise,ivec3(a.x,b.y,b.z)+ivec3(1),0)*v.x*w.y*w.z;
    result+=texelFetch(CloudExactNoise,b+ivec3(1),0)*w.x*w.y*w.z;
#else
    result+=texelFetch(noiseTexture,slice0+ivec2(a.x,a.y),0)*v.x*v.y*v.z;
    result+=texelFetch(noiseTexture,slice0+ivec2(b.x,a.y),0)*w.x*v.y*v.z;
    result+=texelFetch(noiseTexture,slice0+ivec2(a.x,b.y),0)*v.x*w.y*v.z;
    result+=texelFetch(noiseTexture,slice0+ivec2(b.x,b.y),0)*w.x*w.y*v.z;
    result+=texelFetch(noiseTexture,slice1+ivec2(a.x,a.y),0)*v.x*v.y*w.z;
    result+=texelFetch(noiseTexture,slice1+ivec2(b.x,a.y),0)*w.x*v.y*w.z;
    result+=texelFetch(noiseTexture,slice1+ivec2(a.x,b.y),0)*v.x*w.y*w.z;
    result+=texelFetch(noiseTexture,slice1+ivec2(b.x,b.y),0)*w.x*w.y*w.z;
#endif
    return result;
}
vec4 cinematicCloudCellular(sampler2D layoutTexture,sampler2D noiseTexture,vec3 p) {
    return cinematicCloudCellularScale(layoutTexture,noiseTexture,p,512.0);
}
#if CLOUD_LIGHTING_VOLUME == 1
// Lighting-only approximation: hardware interpolation has finite weight
// precision. View extinction/proxy and source diagnostics keep explicit fetches.
vec4 cinematicCloudLightingCellularScale(sampler2D layoutTexture,vec3 p,float scale) {
    vec2 worldPeriod=cinematicCloudPeriod(layoutTexture);
    vec2 repeats=max(ceil(worldPeriod/scale),vec2(1));
    precise vec3 cellSize=vec3(worldPeriod.x/(repeats.x*128.0),scale/128.0,worldPeriod.y/(repeats.y*128.0));
    precise vec3 positionCell=floor(p/cellSize),positionLocal=p-positionCell*cellSize;
    precise vec3 phase=vec3(cinematicCloudPhase(),-cinematicCloudBottom(),3.960000038),phaseCell=floor(phase/cellSize);
    precise vec3 local=positionLocal+(phase-phaseCell*cellSize)-cellSize*0.5,carry=floor(local/cellSize);
    ivec3 cell=ivec3(positionCell+phaseCell+carry)&ivec3(127);
    precise vec3 w=clamp((local-carry*cellSize)/cellSize,vec3(0),vec3(1));
    return textureLod(CloudLightingNoise,(vec3(cell)+vec3(1.5)+w)/130.0,0.0);
}
void cinematicCloudLightingCellularPair(sampler2D layoutTexture,vec3 p,out vec4 coarse,out vec4 detail) {
    vec2 period=cinematicCloudPeriod(layoutTexture);
    // Standard cloud layouts give exact 4m/1m cells. Preserve the general
    // split-lattice path for other texture dimensions instead of changing scale.
    if(any(notEqual(period/512.0,floor(period/512.0)))) {
        coarse=cinematicCloudLightingCellularScale(layoutTexture,p,512.0);
        detail=cinematicCloudLightingCellularScale(layoutTexture,p,128.0);return;
    }
    precise vec3 positionCell=floor(p),positionLocal=p-positionCell;
    precise vec3 shift=vec3(cinematicCloudPhase(),-cinematicCloudBottom(),3.960000038);
    precise vec3 shiftCell=floor(shift),local=positionLocal+(shift-shiftCell)-0.5;
    precise vec3 carry=floor(local),w=local-carry;
    ivec3 cell=ivec3(positionCell+shiftCell+carry);
    detail=textureLod(CloudLightingNoise,(vec3(cell&ivec3(127))+1.5+w)/130.0,0.0);
    // If fine coordinate is n+w, coarse is (n+w+0.5)/4-0.5.
    // Signed shifts and low bits implement floor division/remainder by four.
    precise vec3 coarseLocal=(vec3(cell&ivec3(3))+w)*0.25-0.375;
    precise vec3 coarseCarry=floor(coarseLocal),coarseW=coarseLocal-coarseCarry;
    ivec3 coarseCell=((cell>>2)+ivec3(coarseCarry))&ivec3(127);
    coarse=textureLod(CloudLightingNoise,(vec3(coarseCell)+1.5+coarseW)/130.0,0.0);
}
#endif
#endif
