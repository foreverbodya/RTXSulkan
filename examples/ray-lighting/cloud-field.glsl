#ifndef SULKAN_CINEMATIC_CLOUD_FIELD
#define SULKAN_CINEMATIC_CLOUD_FIELD
#include "shaders/integer-wrap.glsl"
// Continuous layout adapted from the GPL Sulkan 0.5.1 bundled cloud-field.glsl.
// The volume profile and periodic 3D erosion below are local additions.
#ifndef CLOUD_CELL_SIZE
#define CLOUD_CELL_SIZE 48.0
#endif
#ifndef CLOUD_THICKNESS
#define CLOUD_THICKNESS 128.0
#endif
#ifndef CLOUD_EXTINCTION
#define CLOUD_EXTINCTION 0.045
#endif
float cinematicCloudBottom() {return CloudOffsetAndGameTime.y;}
float cinematicCloudPhase() {
#ifdef CLOUD_PREVIEW_STILL
    return 0.0;
#else
    return CloudOffsetAndGameTime.x*(CLOUD_CELL_SIZE/12.0);
#endif
}
vec2 cinematicCloudPeriod(sampler2D layoutTexture) {return vec2(textureSize(layoutTexture,0))*CLOUD_CELL_SIZE;}
vec3 cinematicCloudOrigin(sampler2D layoutTexture) {
    vec2 period=cinematicCloudPeriod(layoutTexture);
    return vec3(mod(CameraPositionHighAndFogType.x,period.x),CameraPositionHighAndFogType.y,
        mod(CameraPositionHighAndFogType.z,period.y))+CameraPositionLowAndFarPlane.xyz;
}
vec3 cinematicCloudLinear(vec3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),greaterThan(c,vec3(0.04045)));
}
vec4 cinematicCloudCell(sampler2D layoutTexture,ivec2 cell) {
    ivec2 size=textureSize(layoutTexture,0),p=ivec2(sulkanWrapInt(cell.x,size.x),sulkanWrapInt(cell.y,size.y));
    vec4 c=texelFetch(layoutTexture,p,0);
    float occupied=step(10.0/255.0,c.a);
#if defined(SULKAN_CLOUD_CELL_FAST_PATH) && SULKAN_CLOUD_CELL_FAST_PATH == 1
    // Exact endpoints of the original transfer function. Colored resource packs
    // keep the original conversion; no density, sample or radiance approximation.
    if(occupied==0.0)return vec4(0);
    if(all(equal(c.rgb,vec3(1))))return vec4(1);
#endif
    return vec4(cinematicCloudLinear(c.rgb)*occupied,occupied);
}
vec4 cinematicCloudField(sampler2D layoutTexture,vec3 p) {
    // Add phase in cell-local coordinates so kilometer-scale positions do not
    // round away the fractional layout footprint before interpolation.
    vec2 positionCell=floor(p.xz/CLOUD_CELL_SIZE),positionLocal=p.xz-positionCell*CLOUD_CELL_SIZE;
    vec2 phase=vec2(cinematicCloudPhase(),3.960000038),phaseCell=floor(phase/CLOUD_CELL_SIZE);
    vec2 local=positionLocal+(phase-phaseCell*CLOUD_CELL_SIZE)-CLOUD_CELL_SIZE*0.5;
    vec2 carry=floor(local/CLOUD_CELL_SIZE);
    ivec2 cell=ivec2(positionCell+phaseCell+carry);
    vec2 w=(local-carry*CLOUD_CELL_SIZE)/CLOUD_CELL_SIZE;w=w*w*(3.0-2.0*w);
    vec4 a=cinematicCloudCell(layoutTexture,cell),b=cinematicCloudCell(layoutTexture,cell+ivec2(1,0));
    vec4 c=cinematicCloudCell(layoutTexture,cell+ivec2(0,1)),d=cinematicCloudCell(layoutTexture,cell+ivec2(1,1));
    vec4 field=mix(mix(a,b,w.x),mix(c,d,w.x),w.y);
    float coverage=smoothstep(0.08,0.85,field.a);
    // Premultiply the linear tint by observable coverage; empty RGB is zero.
    field.rgb*=coverage/max(field.a,0.0001);field.a=coverage;
    return field;
}
uint cinematicCloudHash(ivec3 cell,ivec2 period) {
    cell.x=sulkanWrapInt(cell.x,period.x);cell.z=sulkanWrapInt(cell.z,period.y);
    uint h=uint(cell.x)*0x8da6b343u^uint(cell.y)*0xd8163841u^uint(cell.z)*0xcb1ab31fu;
    h^=h>>16u;h*=0x7feb352du;h^=h>>15u;h*=0x846ca68bu;return h^(h>>16u);
}
vec3 cinematicCloudNoiseCoordinates(sampler2D layoutTexture,vec3 p) {
    vec2 worldPeriod=cinematicCloudPeriod(layoutTexture);
    ivec2 period=ivec2(max(ceil(worldPeriod/32.0),vec2(1)));
    vec2 frequency=vec2(period)/worldPeriod;
    return (p+vec3(cinematicCloudPhase(),-cinematicCloudBottom(),3.960000038))*vec3(frequency.x,1.0/32.0,frequency.y);
}
float cinematicCloudInterpolateNoise(ivec3 cell,vec3 fraction,ivec2 period) {
    vec3 w=fraction*fraction*(3.0-2.0*fraction);
    float result=0.0;
    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        vec3 weight=mix(1.0-w,w,vec3(x,y,z));
        float value=(float(cinematicCloudHash(cell+ivec3(x,y,z),period)&0xffffffu)+0.5)/16777216.0;
        result+=value*weight.x*weight.y*weight.z;
    }
    return result;
}
float cinematicCloudNoise(sampler2D layoutTexture,vec3 p) {
    vec3 q=cinematicCloudNoiseCoordinates(layoutTexture,p);
    return cinematicCloudInterpolateNoise(ivec3(floor(q)),fract(q),
        ivec2(max(ceil(cinematicCloudPeriod(layoutTexture)/32.0),vec2(1))));
}
float cinematicCloudNoiseScale(sampler2D layoutTexture,vec3 p,float scale) {
    vec2 worldPeriod=cinematicCloudPeriod(layoutTexture);
    ivec2 period=ivec2(max(ceil(worldPeriod/scale),vec2(1)));
    // Keep fractional coordinates separate from the large integer lattice.
    // Recombining them into one Float32 q would lose detail after translation.
    vec3 cellSize=vec3(worldPeriod.x/float(period.x),scale,worldPeriod.y/float(period.y));
    vec3 positionCell=floor(p/cellSize),positionLocal=p-positionCell*cellSize;
    vec3 shift=vec3(cinematicCloudPhase(),-cinematicCloudBottom(),3.960000038),shiftCell=floor(shift/cellSize);
    vec3 local=positionLocal+(shift-shiftCell*cellSize),carry=floor(local/cellSize);
    return cinematicCloudInterpolateNoise(ivec3(positionCell+shiftCell+carry),
        clamp((local-carry*cellSize)/cellSize,vec3(0),vec3(1)),period);
}
#include "shaders/cloud-cellular.glsl"
float cinematicCloudDensityImpl(sampler2D layoutTexture,vec3 p,bool lighting) {
    float height=(p.y-cinematicCloudBottom())/CLOUD_THICKNESS;
    if(height<=0.0 || height>=1.0)return 0.0;
    float coverage=cinematicCloudField(layoutTexture,p).a;
    if(coverage<=0.0)return 0.0;
#ifdef CLOUD_CELLULAR_FIELD
    vec4 noise;
#if CLOUD_DENSITY_PROFILE == 1
    vec3 detail;
#if CLOUD_LIGHTING_VOLUME == 1 && REALTIME_CLOUDS != 1
    if(lighting) {
        vec4 fine;cinematicCloudLightingCellularPair(layoutTexture,p,noise,fine);detail=fine.rgb;
    }
    else
#endif
    {
        noise=cinematicCloudCellular(layoutTexture,CloudNoise,p);
        detail=cinematicCloudCellularScale(layoutTexture,CloudNoise,p,128.0).rgb;
    }
    float erosion=1.0-dot(detail,vec3(0.625,0.25,0.125));
    float shape=0.70*noise.r+0.20*noise.g+0.10*noise.a-0.22*erosion;
    float threshold=0.48+0.22*height*height+0.50*(1.0-coverage);
    float body=clamp((shape-threshold)*6.0,0.0,1.0);
#else
#if CLOUD_LIGHTING_VOLUME == 1 && REALTIME_CLOUDS != 1
    if(lighting)noise=cinematicCloudLightingCellularScale(layoutTexture,p,512.0);
    else
#endif
    noise=cinematicCloudCellular(layoutTexture,CloudNoise,p);
    float shape=0.70*noise.r+0.20*noise.g+0.10*noise.a-0.14*(1.0-noise.b);
    float threshold=0.48+0.22*height*height+0.50*(1.0-coverage);
    float body=clamp((shape-threshold)*4.0,0.0,1.0);
#endif
#else
    // The 2D layout is weather coverage, not the extruded cloud boundary.
    // Three periodic scales define independent 3D lobes inside the envelope.
    float coarse=cinematicCloudNoiseScale(layoutTexture,p,64.0);
    float shape=0.625*coarse+0.25*cinematicCloudNoise(layoutTexture,p)
        +0.125*cinematicCloudNoiseScale(layoutTexture,p,16.0);
    float threshold=0.36+0.25*height*height+0.50*(1.0-coverage);
    float body=clamp((shape-threshold)*3.2,0.0,1.0);
#endif
    float envelope=smoothstep(0.0,0.10,height)*(1.0-smoothstep(0.88,1.0,height));
    return CLOUD_EXTINCTION*body*envelope;
}
float cinematicCloudDensity(sampler2D layoutTexture,vec3 p) {
    return cinematicCloudDensityImpl(layoutTexture,p,false);
}
#endif
