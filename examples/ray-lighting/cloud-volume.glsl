#ifndef SULKAN_CINEMATIC_CLOUD_VOLUME
#define SULKAN_CINEMATIC_CLOUD_VOLUME
#include "shaders/cinematic-cloud-field.glsl"
#include "shaders/cloud-scattering.glsl"
#if defined(SULKAN_CLOUD_SOURCE_CACHE_USE) && CLOUD_SOURCE_CACHE == 1
#include "shaders/cloud-source-grid.glsl"
#endif
#if CLOUD_VIEW_DENSITY_SUBSTEPS == 1
#include "shaders/cloud-view-density.glsl"
#endif
// Front-to-back structure adapted from the GPL Sulkan 0.5.1 bundled integrator.
// HDR sources, opacity-weighted guide and bounded transmittance are local work.
bool cinematicCloudSegment(vec3 origin,vec3 direction,float maximum,out float begin,out float end) {
    float bottom=cinematicCloudBottom(),top=bottom+CLOUD_THICKNESS;
    begin=0.0;end=max(maximum,0.0);
    if(abs(direction.y)<1e-6)return origin.y>bottom && origin.y<top && end>0.0;
    float a=(bottom-origin.y)/direction.y,b=(top-origin.y)/direction.y;
    begin=max(min(a,b),0.0);end=min(max(a,b),end);
    return end>begin;
}
float cinematicCloudOpticalDepthImpl(sampler2D layoutTexture,vec3 origin,vec3 direction,float maximum,int samples,bool lighting) {
    float begin,end;
    if(!cinematicCloudSegment(origin,direction,maximum,begin,end))return 0.0;
    int count=clamp(samples,1,512);float ds=(end-begin)/float(count),optical=0.0;
    for(int i=0;i<count;i++)optical+=cinematicCloudDensityImpl(layoutTexture,origin+direction*(begin+(float(i)+0.5)*ds),lighting)*ds;
    return optical;
}
float cinematicCloudOpticalDepth(sampler2D layoutTexture,vec3 origin,vec3 direction,float maximum,int samples) {
    return cinematicCloudOpticalDepthImpl(layoutTexture,origin,direction,maximum,samples,false);
}
float cinematicCloudTransmittance(sampler2D layoutTexture,vec3 origin,vec3 direction,float maximum,int samples) {
    return exp(-cinematicCloudOpticalDepth(layoutTexture,origin,direction,maximum,samples));
}
// xy = vertical optical depths to upper/lower boundary, zw = hemisphere means.
// Explicit plane-parallel ambient closure: lateral cloud escape is not modeled.
vec4 cinematicCloudAmbientColumns(sampler2D layoutTexture,vec3 p) {
    float up=cinematicCloudOpticalDepth(layoutTexture,p,vec3(0,1,0),16384.0,32);
    float down=cinematicCloudOpticalDepth(layoutTexture,p,vec3(0,-1,0),16384.0,32);
    return vec4(up,down,cinematicCloudHemisphereTransmission(up),cinematicCloudHemisphereTransmission(down));
}
// RGB is premultiplied radiance; alpha is transmittance (empty = vec4(0,0,0,1)).
// Each approximate solar order has its own view AND light extinction. Alpha,
// ambient, aerial inscatter and optical proxy use the actual base density only.
// Higher orders add radiance, never additional aerial light or composite opacity.
// Ambient remains an isotropic clear-sky SH closure. Guide = optical proxy.
vec4 cinematicCloudTransport(sampler2D layoutTexture,sampler2D clearLighting,vec3 origin,vec3 direction,
    float maximum,int samples,out float proxyDistance) {
    proxyDistance=0.0;float begin,end;
    if(!cinematicCloudSegment(origin,direction,maximum,begin,end))return vec4(0,0,0,1);
    vec3 sun=cinematicKeyDirection();
    vec3 solar=texelFetch(clearLighting,ivec2(0,0),0).rgb;
    vec3 ambientUp=atmIrradiance(clearLighting,vec3(0,1,0))/6.283185307179586;
    vec3 ambientDown=atmIrradiance(clearLighting,vec3(0,-1,0))/6.283185307179586;
    float cosine=clamp(dot(direction,sun),-1.0,1.0);
    vec3 phase=cinematicCloudOrderPhases(cosine);
    int count=clamp(samples,1,256);
    float stepSize=max(min((end-begin)/float(count),6.0),(end-begin)/256.0);
    vec3 transmission=vec3(1);
    float weightedDistance=0.0,weightSum=0.0;vec3 radiance=vec3(0);
    for(int i=0;i<256;i++) {
        float front=begin+float(i)*stepSize;if(front>=end)break;
        float ds=min(stepSize,end-front),distance=front+ds*0.5;
#if CLOUD_VIEW_DENSITY_SUBSTEPS == 1
        vec4 column=cinematicCloudDensityColumn(layoutTexture,origin+direction*front,direction,ds);
        if(column.x<=0.0)continue;
        distance=front+column.y;
        vec3 p=origin+direction*distance;
        vec3 weight=cinematicCloudStepWeights(column.x,transmission);
#else
        vec3 p=origin+direction*distance;
        float extinction=cinematicCloudDensity(layoutTexture,p);
        if(extinction<=0.0)continue;
        vec3 weight=cinematicCloudStepWeights(extinction*ds,transmission);
#endif
        vec4 field=cinematicCloudField(layoutTexture,p);
        vec3 tint=field.rgb/max(field.a,1e-8);
        vec3 solarVisibility=vec3(0);vec2 ambientVisibility=vec2(0);float referenceWeight=1.0;
#if defined(SULKAN_CLOUD_SOURCE_CACHE_USE) && CLOUD_SOURCE_CACHE == 1
        referenceWeight=cloudSourceLookup(layoutTexture,p,solarVisibility,ambientVisibility);
#endif
        if(referenceWeight>0.0) {
            float lightBegin,lightEnd;int solarSamples=24;
            if(sun.y>0.0 && cinematicCloudSegment(p,sun,16384.0,lightBegin,lightEnd))
                solarSamples=int(clamp(ceil((lightEnd-lightBegin)/4.0),24.0,512.0));
            float optical=sun.y>0.0?cinematicCloudOpticalDepthImpl(layoutTexture,p,sun,16384.0,solarSamples,true):0.0;
            solarVisibility+=referenceWeight*exp(-optical*CLOUD_ORDER_EXTINCTION);
#if CLOUD_AMBIENT_OCCLUSION == 1
            float up=cinematicCloudOpticalDepthImpl(layoutTexture,p,vec3(0,1,0),16384.0,32,true);
            float down=cinematicCloudOpticalDepthImpl(layoutTexture,p,vec3(0,-1,0),16384.0,32,true);
            ambientVisibility+=referenceWeight*vec2(cinematicCloudHemisphereTransmission(up),cinematicCloudHemisphereTransmission(down));
#else
            ambientVisibility+=vec2(referenceWeight);
#endif
        }
        float solarScattering=sun.y>0.0?dot(weight*phase,solarVisibility):0.0;
        vec3 ambient=ambientUp*ambientVisibility.x+ambientDown*ambientVisibility.y;
        vec3 source=0.995*tint*(solar*solarScattering+ambient*weight.x);
        // The aerial helper is affine. Apply its additive source once, weighted
        // by the physical order-zero opacity, rather than once per fake order.
        vec3 air=atmAerialWithLighting(vec3(0),direction,distance,clearLighting);
        vec3 airTransmission=atmBlend()>0.0?atmAirTransmittance(distance):vec3(1);
        radiance+=source*airTransmission+weight.x*air;
        weightedDistance+=weight.x*distance;weightSum+=weight.x;
    }
    if(weightSum>1e-6)proxyDistance=weightedDistance/weightSum;
    return vec4(radiance,transmission.x);
}
#endif
