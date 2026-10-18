#ifndef SULKAN_CINEMATIC_CLOUD_SHADOW
#define SULKAN_CINEMATIC_CLOUD_SHADOW
#include "shaders/cinematic-cloud-volume.glsl"
// Map texels are solar transmission at the fixed world cloud-bottom plane.
// Receivers below the volume project along the sun, not along the camera.
float cinematicCloudWorldSolarVisibility(sampler2D layoutTexture,sampler2D shadowMap,vec3 position,vec3 light) {
    float bottom=cinematicCloudBottom();
    if(light.y<=1e-6 || position.y>=bottom+CLOUD_THICKNESS)return 1.0;
    if(position.y>bottom)return cinematicCloudTransmittance(layoutTexture,position,light,16384.0,64);
    vec3 entry=position+light*((bottom-position.y)/light.y);
    vec2 period=cinematicCloudPeriod(layoutTexture);
    ivec2 size=textureSize(shadowMap,0);
    vec2 footprint=mod(entry.xz,period)/period*vec2(size)-0.5;
    ivec2 cell=ivec2(floor(footprint));vec2 w=fract(footprint);
    float value=0.0;
    for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
        ivec2 texel=ivec2(sulkanWrapInt(cell.x+x,size.x),sulkanWrapInt(cell.y+z,size.y));
        value+=texelFetch(shadowMap,texel,0).r*(x==0?1.0-w.x:w.x)*(z==0?1.0-w.y:w.y);
    }
    // Positive bilinear weights may sum to 1 + one ULP in Float32.
    // This is a probability, so enforce its closed physical interval.
    return clamp(value,0.0,1.0);
}
float cinematicCloudRaySolarVisibility(sampler2D layoutTexture,sampler2D shadowMap,vec3 rayPosition,vec3 light) {
    vec3 position=cinematicCloudOrigin(layoutTexture)+(rayPosition-RayCameraPosition.xyz);
    return mix(1.0,cinematicCloudWorldSolarVisibility(layoutTexture,shadowMap,position,light),atmBlend());
}
#endif
