# Opaque ray visibility diagnostic

This example tests the connection from raster depth through world-position
reconstruction to hardware ray queries on the actual terrain scene. It is not
the cinematic lighting model and does not enable DLSS.

Create a pack with `tools/Create-RayVisibilityPack.ps1 -Destination
'C:/path/to/minecraft/shaders/RayVisibility'`. Select RayVisibility in Sulkan
and launch with `-Dsulkan.rayTracing=true`. Sulkan's external pack directory is
`shaders`, not Minecraft's usual `shaderpacks` directory.

Alternatively run `tools/Run-QaWorld.ps1 ... -RayVisibility` to test it in a
guarded fresh world. The runner checks that the ray pass executes and switches
back to the built-in pack during reload to verify retirement of its resources.

## Pass contract

A compute pass declares `"rayScene": true` in `sulkan.json`. Sulkan provides
`SulkanRayScene` as an acceleration-structure descriptor after the pass's normal
frame, image and storage-buffer bindings, and requests the GLSL ray-query
extension. Device feature negotiation must have succeeded. The pass requires
image outputs and may read storage buffers but cannot write them. While the
scene is empty/loading, outputs are explicitly cleared to zero.

The graphics encoder records incremental scene builds before the ray dispatch.
The frame uploads camera transforms and celestial direction as usual. Normal
pack passes remain independent of ray-query features.

## Image interpretation and limits

White indicates an unoccluded ray toward the sun; black indicates an opaque
terrain blocker within 128 blocks. Blue is sky. Magenta indicates a degenerate
depth-derived normal. Geometry reconstruction uses Vulkan's reversed 0..1 depth
convention already used by Sulkan. This example uses absolute float world
coordinates; distant-coordinate precision remains a production requirement.

Only mask 1 (opaque terrain) casts shadows here. Leaves, transparent surfaces,
entities and wind deformation are not evaluated as blockers. Screen depth may
include those visible surfaces. Edge normals are approximate. There is no
soft-shadow sampling, temporal denoising, indirect illumination or material
evaluation. A picture containing a visibility mask is not evidence of those
features or of the requested 1440p / 100 FPS target.
