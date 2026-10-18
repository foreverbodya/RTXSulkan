#ifndef SULKAN_CINEMATIC_CLOUD_SCATTERING
#define SULKAN_CINEMATIC_CLOUD_SCATTERING
// Finite scattering-order approximation inspired by Hillaire, SIGGRAPH 2016,
// Physically Based Sky, Atmosphere and Cloud Rendering in Frostbite, Eq. 19-20.
// Locally implemented equations; no presentation/sample source code copied.
// a=b=0.5 per order, so scattering/extinction ratio a^n/b^n is one.
// This is not a solved, energy-conserving multiple-scattering model.
const vec3 CLOUD_ORDER_EXTINCTION=vec3(1.0,0.5,0.25);
// Isotropic hemisphere attenuation through a locally plane-parallel column:
// integral_0^1 exp(-verticalOpticalDepth/mu) dmu = E_2(verticalOpticalDepth).
// Eight-point Gauss-Legendre rule, not an exact 3D sky visibility solution.
float cinematicCloudHemisphereTransmission(float verticalOpticalDepth) {
    const float mu[8]=float[8](0.0198550718,0.1016667613,0.2372337950,0.4082826788,
        0.5917173212,0.7627662050,0.8983332387,0.9801449282);
    const float weight[8]=float[8](0.0506142681,0.1111905172,0.1568533229,0.1813418917,
        0.1813418917,0.1568533229,0.1111905172,0.0506142681);
    float sum=0.0;
    for(int i=0;i<8;i++)sum+=weight[i]*exp(-max(verticalOpticalDepth,0.0)/mu[i]);
    return clamp(sum,0.0,1.0);
}
float cinematicCloudPhaseFunction(float cosine,float g) {
    return (1.0-g*g)/(12.566370614359172*pow(max(1.0+g*g-2.0*g*cosine,1e-6),1.5));
}
vec3 cinematicCloudOrderPhases(float cosine) {
    vec3 phase;
    for(int order=0;order<3;order++) {
        float eccentricity=CLOUD_ORDER_EXTINCTION[order];
        phase[order]=0.8*cinematicCloudPhaseFunction(cosine,0.75*eccentricity)
            +0.2*cinematicCloudPhaseFunction(cosine,-0.2*eccentricity);
    }
    return phase;
}
float cinematicCloudSegmentOpacity(float tau,float segmentTransmission) {
    return tau<0.001?tau*(1.0-tau*(0.5-tau/6.0)):1.0-segmentTransmission;
}
vec3 cinematicCloudStepWeights(float opticalDepth,inout vec3 transmission) {
    vec3 tau=max(opticalDepth,0.0)*CLOUD_ORDER_EXTINCTION;
    vec3 segment=exp(-tau),opacity=1.0-segment;
    for(int order=0;order<3;order++)opacity[order]=cinematicCloudSegmentOpacity(tau[order],segment[order]);
    vec3 weight=transmission*opacity;
    transmission*=segment;
    return weight;
}
#endif
