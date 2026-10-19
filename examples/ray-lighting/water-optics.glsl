#ifndef WATER_SURFACE
#define WATER_SURFACE 0
#endif
const float WATER_IOR=1.3333333333;
#if WATER_SURFACE == 1
// Look preset: slightly deeper red absorption and a little more green-blue
// scattering, so deep water settles into a soft teal instead of flat blue.
const vec3 WATER_SIGMA_A=vec3(0.26,0.060,0.032);
const vec3 WATER_SIGMA_S=vec3(0.010,0.028,0.026);
#else
const vec3 WATER_SIGMA_A=vec3(0.22,0.06,0.025);
const vec3 WATER_SIGMA_S=vec3(0.012,0.025,0.030);
#endif
const vec3 WATER_SIGMA_T=WATER_SIGMA_A+WATER_SIGMA_S;
float waterFresnel(float cosine,float eta) {
    float c=clamp(cosine,0.0,1.0),sin2=eta*eta*max(1.0-c*c,0.0);
    if(sin2>=1.0)return 1.0;
    float t=sqrt(1.0-sin2),rs=(eta*c-t)/(eta*c+t),rp=(eta*t-c)/(eta*t+c);
    return 0.5*(rs*rs+rp*rp);
}
vec3 waterTransmittance(float distance) {return exp(-WATER_SIGMA_T*max(distance,0.0));}
vec3 waterEnvironmentDisk(vec3 direction,bool keyDisk) {
    float daylight=smoothstep(-0.05,0.15,SunDirectionAndRainBrightness.y);
    vec3 incoming=mix(vec3(0.015,0.02,0.04),mix(vec3(0.22,0.28,0.38),vec3(0.12,0.25,0.5),clamp(direction.y,0.0,1.0)),daylight);
#ifdef CINEMATIC_ATMOSPHERE
#ifdef CLOUD_PREVIEW_STILL
    incoming=mix(incoming,atmVisibleSkyLayerDisk(AtmosphereSky,AtmosphereLighting,direction,true,keyDisk),atmBlend());
#else
    incoming=mix(incoming,atmVisibleSkyLayerDisk(AtmosphereSky,AtmosphereLighting,direction,false,keyDisk),atmBlend());
#endif
#endif
    return incoming;
}
vec3 waterEnvironment(vec3 direction) {return waterEnvironmentDisk(direction,true);}
vec3 waterScatter(float distance,vec3 direction,vec3 normal,vec3 lightWater,vec3 solarWater,vec3 solarVisibility) {
    vec3 trans=waterTransmittance(distance);
    vec3 sky=vec3(0.12,0.16,0.23);
#ifdef CINEMATIC_ATMOSPHERE
    sky=mix(sky,atmIrradiance(AtmosphereLighting,normal)/SULKAN_LIGHT_PI,atmBlend());
#endif
    // Bounded isotropic sky-source approximation; not a full angular medium solve.
    vec3 result=WATER_SIGMA_S/WATER_SIGMA_T*(1.0-trans)*sky*0.5;
    float cosine=dot(normal,lightWater);
    if(cosine>0.0001) {
        float k=1.0-dot(normal,direction)/cosine;
        vec3 integral=(1.0-exp(-WATER_SIGMA_T*k*distance))/(WATER_SIGMA_T*k);
        result+=WATER_SIGMA_S*solarWater*solarVisibility*integral/(4.0*SULKAN_LIGHT_PI);
    }
    return result;
}
#if WATER_SURFACE == 1
// Animated surface for the look preset. Shading-only: geometry, depth, motion
// and the planar interface used for submerged sun paths stay flat.
// Base GGX alpha^2 for unresolved capillary ripples (alpha ~= 0.03).
const float WATER_GLINT_ALPHA2=0.0009;
struct WaterWaves {vec2 slope;float laplacian;float lostVariance;};
// The wave field tiles every WATER_WAVE_PERIOD blocks: wave vectors snap to
// multiples of 2*pi/period. RaySceneOrigin is an exact multiple of 256, so
// mod(origin, period) is exact in float32 and the origin-relative position stays
// small; phase is continuous across origin rebases and far from spawn. Never add
// the absolute origin to a float position before evaluating waves.
const float WATER_WAVE_PERIOD=1024.0;
vec2 waterWaveCoordinate(vec3 originRelative) {
    return mod(RaySceneOrigin.xz,WATER_WAVE_PERIOD)+originRelative.xz;
}
// Sum of directional sines, wavelengths 7 .. 0.15 blocks, deep-water dispersion
// with 1 block = 1 m. Directions stay within ~60 degrees of one wind direction.
// Octaves below the pixel footprint fade out and their slope variance moves
// into the glint roughness instead (Toksvig-style), so distant water calms into
// a soft sun path rather than aliasing.
WaterWaves waterWaves(vec2 p,float time,float footprint,float minimumWavelength) {
    WaterWaves w=WaterWaves(vec2(0),0.0,0.0);
    float steepness=0.032+0.025*clamp(WorldTimeWeatherDimension.y,0.0,1.0);
    float wavelength=7.0;
    for(int i=0;i<9;i++) {
        float angle=0.35+1.05*sin(float(i)*2.399963);
        const float quantum=6.283185307/WATER_WAVE_PERIOD;
        vec2 wave=round(vec2(cos(angle),sin(angle))*(6.283185307/wavelength/quantum))*quantum;
        float k=length(wave),omega=sqrt(9.81*k);
        vec2 direction=wave/k;
        float phase=dot(wave,p)-omega*time+float(i)*1.7;
        float fade=smoothstep(2.0*footprint,6.0*footprint,wavelength)*step(minimumWavelength,wavelength);
        float slope=steepness*(i<3?0.5:1.0);
        w.slope+=direction*(slope*cos(phase)*fade);
        w.laplacian-=slope*k*sin(phase)*fade;
        w.lostVariance+=0.5*slope*slope*(1.0-fade*fade);
        wavelength*=0.62;
    }
    return w;
}
// Perturbs the view-facing geometric normal and keeps it visible from the camera.
// Slopes calm toward grazing views, where small tilts swing reflections into
// large high-contrast patches.
vec3 waterWaveNormal(vec3 geometric,vec3 incident,vec2 slope) {
    slope*=mix(0.45,1.0,smoothstep(0.05,0.4,dot(geometric,-incident)));
    vec3 n=normalize(geometric-vec3(slope.x,0,slope.y));
    float c=dot(n,-incident);
    return c<0.05?normalize(n-incident*(0.05-c)):n;
}
// Keeps secondary rays on their side of the flat interface they start from.
vec3 waterAbove(vec3 direction,vec3 geometric) {
    float c=dot(direction,geometric);
    return c<0.01?normalize(direction+geometric*(0.01-c)):direction;
}
vec3 waterBelow(vec3 direction,vec3 geometric) {
    float c=dot(direction,geometric);
    return c>-0.01?normalize(direction-geometric*(0.01+c)):direction;
}
// Sun glitter: GGX specular from the key light, returned as reflected radiance
// per unit perpendicular irradiance. Replaces the sky's mirror sun disk.
float waterGlint(vec3 n,vec3 view,vec3 light,float alpha2,float eta) {
    float nl=dot(n,light),nv=dot(n,view);
    if(nl<=0.0 || nv<=0.0)return 0.0;
    vec3 h=normalize(view+light);
    float nh=max(dot(n,h),0.0),d=nh*nh*(alpha2-1.0)+1.0;
    float distribution=alpha2/(SULKAN_LIGHT_PI*d*d);
    float g=2.0*nv/(nv+sqrt(alpha2+(1.0-alpha2)*nv*nv))*2.0*nl/(nl+sqrt(alpha2+(1.0-alpha2)*nl*nl));
    return waterFresnel(dot(view,h),eta)*distribution*g/(4.0*nv);
}
// Refractive caustic intensity at depth below the entry point, from the
// Jacobian of the refracted-ray map (small-slope approximation). Clamped and
// stylized: not energy conserving at focus.
float waterCaustic(WaterWaves waves,float depth,float eta) {
    float jacobian=1.0+depth*(1.0-eta)*waves.laplacian;
    return clamp(1.0/max(abs(jacobian),0.5),0.6,1.7);
}
#endif
