const float WATER_IOR=1.3333333333;
const vec3 WATER_SIGMA_A=vec3(0.22,0.06,0.025);
const vec3 WATER_SIGMA_S=vec3(0.012,0.025,0.030);
const vec3 WATER_SIGMA_T=WATER_SIGMA_A+WATER_SIGMA_S;
float waterFresnel(float cosine,float eta) {
    float c=clamp(cosine,0.0,1.0),sin2=eta*eta*max(1.0-c*c,0.0);
    if(sin2>=1.0)return 1.0;
    float t=sqrt(1.0-sin2),rs=(eta*c-t)/(eta*c+t),rp=(eta*t-c)/(eta*t+c);
    return 0.5*(rs*rs+rp*rp);
}
vec3 waterTransmittance(float distance) {return exp(-WATER_SIGMA_T*max(distance,0.0));}
vec3 waterEnvironment(vec3 direction) {
    float daylight=smoothstep(-0.05,0.15,SunDirectionAndRainBrightness.y);
    vec3 incoming=mix(vec3(0.015,0.02,0.04),mix(vec3(0.22,0.28,0.38),vec3(0.12,0.25,0.5),clamp(direction.y,0.0,1.0)),daylight);
#ifdef CINEMATIC_ATMOSPHERE
#ifdef CLOUD_PREVIEW_STILL
    incoming=mix(incoming,atmVisibleCloudSky(AtmosphereSky,AtmosphereLighting,direction),atmBlend());
#else
    incoming=mix(incoming,atmVisibleSky(AtmosphereSky,AtmosphereLighting,direction),atmBlend());
#endif
#endif
    return incoming;
}
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
