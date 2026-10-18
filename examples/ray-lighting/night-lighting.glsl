#ifndef SULKAN_NIGHT_LIGHTING
#define SULKAN_NIGHT_LIGHTING
#ifndef NIGHT_SKY
#define NIGHT_SKY 0
#endif
// Scene-relative artistic scale, not measured lunar radiometry. Phase follows
// the projected Lambert sphere; engine phase zero is full and four is new.
const float CINEMATIC_MOON_SCALE=0.0025;
const vec3 CINEMATIC_MOON_TINT=vec3(0.68,0.80,1.0);
const float CINEMATIC_MOON_RADIUS=0.00935;
float cinematicMoonPhase() {
    float angle=acos(clamp(cos(MoonDirectionAndPhase.w*0.7853981633974483),-1.0,1.0));
    return max((sin(angle)+(3.141592653589793-angle)*cos(angle))/3.141592653589793,0.0);
}
bool cinematicMoonKey() {
#if NIGHT_SKY == 1
    return SunDirectionAndRainBrightness.y<0.0 && dot(MoonDirectionAndPhase.xyz,MoonDirectionAndPhase.xyz)>1e-12;
#else
    return false;
#endif
}
vec3 cinematicKeyDirection() {return normalize(cinematicMoonKey()?MoonDirectionAndPhase.xyz:SunDirectionAndRainBrightness.xyz);}
float cinematicKeyScale() {return cinematicMoonKey()?CINEMATIC_MOON_SCALE*cinematicMoonPhase():1.0;}
vec3 cinematicKeyTint() {return cinematicMoonKey()?CINEMATIC_MOON_TINT:vec3(1);}
float cinematicNightAmount() {
#if NIGHT_SKY == 1
    return 1.0-smoothstep(-0.25,-0.04,SunDirectionAndRainBrightness.y);
#else
    return 0.0;
#endif
}
float cinematicExposureKey() {return mix(0.18,0.018,cinematicNightAmount());}
float cinematicExposureMaximum() {return mix(5.0,10.0,cinematicNightAmount());}
#endif
