#ifndef SULKAN_CLOUD_VIEW_DENSITY
#define SULKAN_CLOUD_VIEW_DENSITY
#include "shaders/cinematic-cloud-field.glsl"
#include "shaders/cloud-scattering.glsl"
// Shared sample coordinates for production integration and captured diagnostics.
vec4 cinematicCloudDensitySample(sampler2D layoutTexture,vec3 origin,vec3 direction,float distance) {
    precise vec3 position=origin+direction*distance;
    return vec4(position,cinematicCloudDensity(layoutTexture,position));
}
// x=optical column, y=local opacity centroid, z=local opacity, w=substep length.
// View segments are <=8000m with <=256 lighting intervals, so each interval
// is <=31.25m. At most 32 midpoint density samples provide a <=1m subgrid.
vec4 cinematicCloudDensityColumn(sampler2D layoutTexture,vec3 origin,vec3 direction,float length) {
    int count=clamp(int(ceil(length)),1,32);
    float ds=length/float(count),optical=0.0,transmission=1.0,moment=0.0,opacity=0.0;
    for(int i=0;i<count;i++) {
        float distance=(float(i)+0.5)*ds;
        float tau=cinematicCloudDensitySample(layoutTexture,origin,direction,distance).w*ds;
        float segment=exp(-tau),weight=transmission*cinematicCloudSegmentOpacity(tau,segment);
        moment+=weight*distance;opacity+=weight;optical+=tau;transmission*=segment;
    }
    return vec4(optical,opacity>0.0?moment/opacity:length*0.5,opacity,ds);
}
#endif
