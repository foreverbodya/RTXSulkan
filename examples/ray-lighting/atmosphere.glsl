#ifndef SULKAN_ATMOSPHERE
#define SULKAN_ATMOSPHERE
#include "sulkan/camera-rays.glsl"
#include "shaders/integer-wrap.glsl"
#include "shaders/sun-source.glsl"
// RGB atmosphere, lengths in km and coefficients in inverse km.
// Three representative wavelengths; not spectral/colorimetric calibration.
const float ATM_PI=3.141592653589793;
const float ATM_RADIUS=6360.0;
const float ATM_TOP=6420.0;
const vec3 ATM_RAYLEIGH=vec3(0.005802,0.013558,0.033100);
const vec3 ATM_OZONE=vec3(0.0007916,0.0016611,0.0001245);
const float ATM_MIE_SCATTER=0.003996;
const float ATM_MIE_EXTINCT=0.004440;
const float ATM_SOLAR=3.5; // Relative scene irradiance units, not W/m^2.
float atmObserverHeight() {
    return clamp((CameraPositionHighAndFogType.y+CameraPositionLowAndFarPlane.y-64.0)*0.001,0.001,50.0);
}
float atmBlend() {
    if(WorldTimeWeatherDimension.w!=0.0 || CameraPositionHighAndFogType.w!=0.0
        || dot(SunDirectionAndRainBrightness.xyz,SunDirectionAndRainBrightness.xyz)<1e-12) return 0.0;
#if NIGHT_SKY == 1
    return 1.0-clamp(WorldTimeWeatherDimension.y,0.0,1.0);
#else
    return smoothstep(-0.15,-0.08,SunDirectionAndRainBrightness.y)*(1.0-clamp(WorldTimeWeatherDimension.y,0.0,1.0));
#endif
}
vec3 atmDensity(float height) {
    height=max(height,0.0);
    return vec3(exp(-height/8.0),exp(-height/1.2),max(0.0,1.0-abs(height-25.0)/15.0));
}
vec3 atmExtinction(vec3 density) { return ATM_RAYLEIGH*density.x+vec3(ATM_MIE_EXTINCT*density.y)+ATM_OZONE*density.z; }
// Rationalized altitude and ground intersection avoid subtracting two Earth-sized floats.
float atmHeightAlong(float height,float mu,float distance) {
    float delta=height*(2.0*ATM_RADIUS+height)+distance*(2.0*(ATM_RADIUS+height)*mu+distance);
    return delta/(sqrt(max(ATM_RADIUS*ATM_RADIUS+delta,1.0))+ATM_RADIUS);
}
float atmTopDistance(float height,float mu) {
    float b=(ATM_RADIUS+height)*mu;
    float c=(ATM_TOP-ATM_RADIUS-height)*(ATM_TOP+ATM_RADIUS+height);
    return max(0.0,sqrt(max(b*b+c,0.0))-b);
}
float atmGroundDistance(float height,float mu) {
    float b=(ATM_RADIUS+height)*mu;
    float c=height*(2.0*ATM_RADIUS+height);
    float discriminant=b*b-c;
    return mu<0.0 && discriminant>=0.0 ? c/max(-b+sqrt(discriminant),1e-8) : -1.0;
}
vec3 atmSunTransmittance(float height,float mu,int steps) {
    if(atmGroundDistance(height,mu)>=0.0) return vec3(0);
    float maximum=atmTopDistance(height,mu);
    vec3 optical=vec3(0);
    for(int i=0;i<steps;i++) {
        float a=float(i)/float(steps),b=float(i+1)/float(steps);
        float begin=maximum*a*a,end=maximum*b*b;
        optical+=atmDensity(atmHeightAlong(height,mu,(begin+end)*0.5))*(end-begin);
    }
    return exp(-atmExtinction(optical));
}
vec2 atmPhase(float cosine) {
    float rayleigh=3.0/(16.0*ATM_PI)*(1.0+cosine*cosine);
    const float g=0.8;
    float mie=3.0/(8.0*ATM_PI)*(1.0-g*g)*(1.0+cosine*cosine)
        /((2.0+g*g)*pow(max(1.0+g*g-2.0*g*cosine,1e-6),1.5));
    return vec2(rayleigh,mie);
}
vec3 atmMultiple(sampler2D table,float height,float muSun) {
    ivec2 size=textureSize(table,0);
    vec2 grid=vec2(clamp(muSun*0.5+0.5,0.0,1.0),sqrt(clamp((height-0.001)/59.999,0.0,1.0)))*vec2(size-1);
    ivec2 p=ivec2(floor(grid)),q=min(p+1,size-1);vec2 f=fract(grid);
    return mix(mix(texelFetch(table,p,0).rgb,texelFetch(table,ivec2(q.x,p.y),0).rgb,f.x),
        mix(texelFetch(table,ivec2(p.x,q.y),0).rgb,texelFetch(table,q,0).rgb,f.x),f.y);
}
vec4 atmIntegrate(vec3 direction,vec3 sun,float height
#if ATM_MULTIPLE_SCATTERING == 1
    ,sampler2D multiple
#endif
) {
    float maximum=atmTopDistance(height,direction.y);
    float ground=atmGroundDistance(height,direction.y);
    if(ground>=0.0) maximum=min(maximum,ground);
    vec2 phase=atmPhase(clamp(dot(direction,sun),-1.0,1.0));
    vec3 transmittance=vec3(1),radiance=vec3(0);
    for(int i=0;i<32;i++) {
        float a=float(i)/32.0,b=float(i+1)/32.0;
        float begin=maximum*a*a,end=maximum*b*b,middle=(begin+end)*0.5;
        float altitude=atmHeightAlong(height,direction.y,middle);
        vec3 density=atmDensity(altitude),extinction=atmExtinction(density);
        float muSun=((ATM_RADIUS+height)*sun.y+middle*dot(direction,sun))/(ATM_RADIUS+altitude);
        vec3 sunlight=atmSunTransmittance(altitude,clamp(muSun,-1.0,1.0),12);
        vec3 segment=exp(-extinction*(end-begin));
        vec3 weight=(1.0-segment)/max(extinction,vec3(1e-9));
        vec3 source=sunlight*(ATM_RAYLEIGH*density.x*phase.x+vec3(ATM_MIE_SCATTER*density.y*phase.y));
#if ATM_MULTIPLE_SCATTERING == 1
        source+=(ATM_RAYLEIGH*density.x+vec3(ATM_MIE_SCATTER*density.y))*atmMultiple(multiple,altitude,muSun);
#endif
        radiance+=transmittance*weight*source;
        transmittance*=segment;
    }
    if(ground>=0.0) {
        float muSun=((ATM_RADIUS+height)*sun.y+ground*dot(direction,sun))/ATM_RADIUS;
        // Average Lambertian planet ground, used only by the environment approximation.
        radiance+=transmittance*(0.1/ATM_PI)*max(muSun,0.0)*atmSunTransmittance(0.001,clamp(muSun,-1.0,1.0),12);
    }
    return vec4(max(radiance*ATM_SOLAR,vec3(0)),clamp(dot(transmittance,vec3(1.0/3.0)),0.0,1.0));
}
vec3 atmLutDirection(vec2 uv) {
    float vertical=uv.y*2.0-1.0;
    float mu=sign(vertical)*vertical*vertical;
    float phi=(uv.x-0.5)*(2.0*ATM_PI);
    return vec3(cos(phi)*sqrt(max(1.0-mu*mu,0.0)),mu,sin(phi)*sqrt(max(1.0-mu*mu,0.0)));
}
vec4 atmSkySample(sampler2D sky,vec3 direction) {
    float azimuth=dot(direction.xz,direction.xz)>1e-12 ? atan(direction.z,direction.x) : 0.0;
    vec2 uv=vec2(azimuth/(2.0*ATM_PI)+0.5,
        0.5+0.5*sign(direction.y)*sqrt(abs(direction.y)));
    ivec2 size=textureSize(sky,0);
    vec2 location=uv*vec2(size)-0.5,fraction=fract(location);
    ivec2 base=ivec2(floor(location));
    int x0=sulkanWrapInt(base.x,size.x),x1=sulkanWrapInt(x0+1,size.x);
    int y0=clamp(base.y,0,size.y-1),y1=clamp(base.y+1,0,size.y-1);
    return mix(mix(texelFetch(sky,ivec2(x0,y0),0),texelFetch(sky,ivec2(x1,y0),0),fraction.x),
        mix(texelFetch(sky,ivec2(x0,y1),0),texelFetch(sky,ivec2(x1,y1),0),fraction.x),fraction.y);
}
vec3 atmSky(sampler2D sky,vec3 direction) {return atmSkySample(sky,direction).rgb;}
float atmBasis(int index,vec3 d) {
    if(index==0) return 0.282094792;
    if(index==1) return 0.488602512*d.z;
    if(index==2) return 0.488602512*d.y;
    if(index==3) return 0.488602512*d.x;
    if(index==4) return 1.092548431*d.x*d.z;
    if(index==5) return 1.092548431*d.z*d.y;
    if(index==6) return 0.315391565*(3.0*d.y*d.y-1.0);
    if(index==7) return 1.092548431*d.x*d.y;
    return 0.546274215*(d.x*d.x-d.z*d.z);
}
vec3 atmIrradiance(sampler2D lighting,vec3 normal) {
    vec3 result=vec3(0);
    for(int i=0;i<9;i++) result+=texelFetch(lighting,ivec2(i+1,0),0).rgb*atmBasis(i,normal);
    return max(result,vec3(0));
}
vec3 atmAirTransmittance(float distance) { return exp(-atmExtinction(atmDensity(atmObserverHeight()))*max(distance,0.0)*0.001); }
vec3 atmAerial(vec3 radiance,vec3 direction,float distance,vec3 solar) {
    if(atmBlend()<=0.0) return radiance;
    vec3 density=atmDensity(atmObserverHeight()),extinction=atmExtinction(density);
    vec3 transmittance=atmAirTransmittance(distance);
    vec2 phase=atmPhase(clamp(dot(direction,cinematicKeyDirection()),-1.0,1.0));
    vec3 inscatter=solar*(ATM_RAYLEIGH*density.x*phase.x+vec3(ATM_MIE_SCATTER*density.y*phase.y))
        *(1.0-transmittance)/max(extinction,vec3(1e-9));
    return radiance*transmittance+inscatter;
}
vec3 atmAerialWithLighting(vec3 radiance,vec3 direction,float distance,sampler2D lighting) {
    vec3 result=atmAerial(radiance,direction,distance,texelFetch(lighting,ivec2(0,0),0).rgb);
#if ATM_MULTIPLE_SCATTERING == 1
    if(atmBlend()>0.0) {
        vec3 density=atmDensity(atmObserverHeight()),extinction=atmExtinction(density);
        vec3 scattering=ATM_RAYLEIGH*density.x+vec3(ATM_MIE_SCATTER*density.y);
        result+=scattering*texelFetch(lighting,ivec2(10,0),0).rgb*(1.0-atmAirTransmittance(distance))/max(extinction,vec3(1e-9));
    }
#endif
    return result;
}
#if NIGHT_SKY == 1
uint atmStarHash(uvec3 p) {
    uint h=p.x*0x8da6b343u^p.y*0xd8163841u^p.z*0xcb1ab31fu;
    h^=h>>16u;h*=0x7feb352du;h^=h>>15u;h*=0x846ca68bu;return h^(h>>16u);
}
vec3 atmStars(vec3 d,float angularPixel) {
    vec3 a=abs(d);vec2 q;uint face;
    if(a.x>=a.y && a.x>=a.z){q=d.yz/a.x;face=d.x>0?0u:1u;}
    else if(a.y>=a.z){q=d.xz/a.y;face=d.y>0?2u:3u;}
    else {q=d.xy/a.z;face=d.z>0?4u:5u;}
    vec2 grid=(q*0.5+0.5)*384.0;ivec2 cell=ivec2(floor(grid));
    uint h=atmStarHash(uvec3(uvec2(cell),face));
    if((h&1023u)>3u)return vec3(0);
    vec2 centre=vec2(float((h>>10u)&1023u),float((h>>20u)&1023u))/1023.0;
    centre=mix(vec2(0.2),vec2(0.8),centre);
    vec2 delta=fract(grid)-centre;
    float sigma=max(0.075,angularPixel*192.0/max(max(a.x,a.y),a.z));
    float profile=exp(-dot(delta,delta)/(2.0*sigma*sigma))*0.075*0.075/(sigma*sigma);
    float brightness=0.0045*mix(0.25,1.0,float(h>>24u)/255.0);
    vec3 tint=mix(vec3(0.63,0.77,1.0),vec3(1.0,0.84,0.65),float((h>>4u)&63u)/63.0);
    return tint*brightness*profile*cinematicNightAmount()*smoothstep(0.01,0.15,d.y);
}
vec3 atmMoonDisk(sampler2D lighting,vec3 d,float angularPixel) {
    vec3 moon=normalize(MoonDirectionAndPhase.xyz);
    float cosine=dot(d,moon),radius=CINEMATIC_MOON_RADIUS;
    if(moon.y<=0.0 || cosine<cos(radius+angularPixel))return vec3(0);
    vec3 t=normalize(abs(moon.y)<0.99?cross(vec3(0,1,0),moon):cross(vec3(1,0,0),moon));
    vec3 b=cross(moon,t);vec2 p=vec2(dot(d,t),dot(d,b))/sin(radius);
    float r2=dot(p,p),edge=1.0-smoothstep(1.0-angularPixel/radius,1.0+angularPixel/radius,r2);
    vec3 n=vec3(p,sqrt(max(1.0-r2,0.0)));
    float angle=MoonDirectionAndPhase.w*0.7853981633974483;
    float shade=max(dot(n,vec3(sin(angle),0,cos(angle))),0.0);
    float maria=1.0;
    maria-=0.30*(1.0-smoothstep(0.18,0.30,length(n.xy-vec2(-0.30,0.18))));
    maria-=0.25*(1.0-smoothstep(0.22,0.34,length(n.xy-vec2(0.24,0.26))));
    maria-=0.23*(1.0-smoothstep(0.13,0.24,length(n.xy-vec2(0.04,-0.37))));
    maria+=0.025*sin(23.0*n.x)*sin(29.0*n.y);
    vec3 irradiance=texelFetch(lighting,ivec2(0,0),0).rgb;
    // Artistic visible-disk compression keeps lunar markings readable at the
    // world's night exposure. It does not rescale the traced lunar irradiance.
    // This deliberately breaks radiometric disk/source equivalence.
    return cinematicMoonKey()?irradiance*(1.5*shade*edge*maria/(2048.0*3.141592653589793*radius*radius*max(cinematicMoonPhase(),1e-6))):vec3(0);
}
#endif
// sunDisk=false omits only the solar disk, for callers that integrate the sun
// through their own BRDF lobe (wavy water glint).
vec3 atmVisibleSkyLayerDisk(sampler2D sky,sampler2D lighting,vec3 direction,bool cloudTransmission,bool sunDisk) {
    if(atmBlend()<=0.0) return vec3(0);
    float cosine=dot(direction,normalize(SunDirectionAndRainBrightness.xyz));
    float angularPixel=2.0/(max(abs(Projection[1][1]),0.01)*ViewSizeAndInverse.y);
    float disk=sunDisk?smoothstep(cos(CINEMATIC_SUN_RADIUS+angularPixel*0.5),cos(max(CINEMATIC_SUN_RADIUS-angularPixel*0.5,0.0001)),cosine):0.0;
    vec4 environment=atmSkySample(sky,direction);
    vec3 celestial=texelFetch(lighting,ivec2(0,0),0).rgb*(disk/0.00006866);
#if NIGHT_SKY == 1
    if(cinematicMoonKey())celestial=vec3(0);
    celestial+=atmMoonDisk(lighting,direction,angularPixel)+atmStars(direction,angularPixel);
#endif
    return environment.rgb+celestial*(cloudTransmission?environment.a:1.0);
}
vec3 atmVisibleSkyLayer(sampler2D sky,sampler2D lighting,vec3 direction,bool cloudTransmission) {
    return atmVisibleSkyLayerDisk(sky,lighting,direction,cloudTransmission,true);
}
vec3 atmVisibleSky(sampler2D sky,sampler2D lighting,vec3 direction) {return atmVisibleSkyLayer(sky,lighting,direction,false);}
vec3 atmVisibleCloudSky(sampler2D sky,sampler2D lighting,vec3 direction) {return atmVisibleSkyLayer(sky,lighting,direction,true);}
#endif
