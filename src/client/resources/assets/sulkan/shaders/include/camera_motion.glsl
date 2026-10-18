#include "sulkan/camera-rays.glsl"
// Previous-minus-current motion in render/input pixels for a static world surface.
// Caller supplies SulkanFrame. Animated geometry must write its own motion.
// Sky is a world-space direction at infinity: camera translation has no effect.
bool sulkanSkyCameraMotion(vec2 uv, out vec2 motion) {
    motion=vec2(0);
    if(CameraDeltaAndHistoryValid.w<0.5)return false;
    vec3 direction=sulkanCameraRay(uv).direction;
    if(any(isnan(direction)) || any(isinf(direction)))return false;
    vec4 previous=PreviousProjection*vec4(mat3(PreviousView)*direction,0.0);
    if(previous.w<=1e-8 || any(isnan(previous)) || any(isinf(previous)))return false;
    motion=(previous.xy/previous.w*0.5+0.5-uv)*ViewSizeAndInverse.xy;
    motion+=CurrentAndPreviousJitter.xy-CurrentAndPreviousJitter.zw;
    return !any(isnan(motion)) && !any(isinf(motion)) && all(lessThanEqual(abs(motion),vec2(65504.0)));
}
bool sulkanStaticCameraMotion(vec2 uv, float depth, out vec2 motion) {
    motion=vec2(0);
    if (CameraDeltaAndHistoryValid.w<0.5 || depth<=1e-7) return false;
    vec4 eye=InverseProjection*vec4(uv*2.0-1.0,depth,1.0);
    if (abs(eye.w)<1e-8 || any(isnan(eye)) || any(isinf(eye))) return false;
    vec3 relative=mat3(InverseView)*(eye.xyz/eye.w);
    vec4 previous=PreviousProjection*PreviousView*vec4(relative+CameraDeltaAndHistoryValid.xyz,1.0);
    if (previous.w<=1e-8 || any(isnan(previous)) || any(isinf(previous))) return false;
    motion=(previous.xy/previous.w*0.5+0.5-uv)*ViewSizeAndInverse.xy;
    // NGX motion excludes sample jitter even though depth uses the jittered projection.
    motion+=CurrentAndPreviousJitter.xy-CurrentAndPreviousJitter.zw;
    return !any(isnan(motion)) && !any(isinf(motion));
}
