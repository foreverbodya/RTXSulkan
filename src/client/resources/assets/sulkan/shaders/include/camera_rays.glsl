#ifndef SULKAN_CAMERA_RAYS
#define SULKAN_CAMERA_RAYS
// Perspective world projection may contain Minecraft's camera bob translation.
// InverseProjection[2] is its perspective centre (clip Z basis, clip W = 0).
// Subtract homogeneous points before normalization: a near-plane point alone
// is not a direction when the optical centre differs from the nominal camera.
struct SulkanCameraRay { vec3 origin; vec3 direction; };
SulkanCameraRay sulkanCameraRay(vec2 uv) {
    vec4 centre=InverseView*InverseProjection[2];
    vec4 farPoint=InverseView*(InverseProjection*vec4(uv*2.0-1.0,0.0,1.0));
    return SulkanCameraRay(centre.xyz/centre.w,
        normalize(farPoint.xyz*centre.w-centre.xyz*farPoint.w));
}
#endif
