# Leaf transmission checkpoint

2026-10-15. Optional stationary-leaf lighting, not a complete cinematic shaderpack.
Original implementation; no third-party renderer source was imported for it.

## Model and integration

Generate the experimental pack with `Create-RayLightingPack.ps1
-Destination <new-directory> -FoliageTransmission`. The switch defaults off.
Use `-Atmosphere`, `-Reflections` and `-DlssReconstruction` for the integrated
configuration. `-FoliageDiagnostic` adds GPU readback targets for conformance;
normal forest timing runs do not enable that extra pass.

For linear RGB leaf albedo a, the artistic preset uses tau=0.5*sqrt(a),
rho=a*(1-tau), dielectric Schlick Fresnel with F0=0.04, and a thin-sheet
BTDF tau*(1-F(in))*(1-F(out))/pi. Rho+tau is bounded by one for a in [0,1].
That coefficient bound does not establish physical energy conservation of the
entire renderer. Leaf optics and thickness have not been measured or calibrated.

Primary leaf surfaces use rho for front diffuse and the BTDF for rear sunlight;
the front GGX specular term remains. Front and rear atmospheric irradiance share
the sky model. Ambient fill retains vanilla skylight and the legacy brightness/AO
factor. It is not traced GI. Secondary reflected leaf lighting uses the same
transport coefficients with a simpler Lambertian front-light approximation.

Solar rays force nonopaque traversal and enumerate all triangle candidates.
Source material alpha and cutout thresholds reject holes. An accepted non-leaf
blocks all light. Accepted leaf crossings with geometric normal dot direction
below zero multiply colored transmission and the two interface weights. Exit
crossings do not attenuate again. The product does not depend on candidate order.
This handles opposite coincident entry/exit faces without repeatedly asking for
the nearest surface. Meshes with culled interior boundaries still do not encode
leaf volume or density. This is an oriented-boundary approximation.

Ray instance mask 3 covers opaque/cutout terrain. Translucent glass and water
are excluded. The maximum solar distance is 128 blocks; after 2048 candidates,
or transmission below 0.001 in every channel, the conservative result is zero.
LOD0 alpha/texture sampling is intentional for this fixture; wind, texture
footprints, mip policies, custom leaf meshes and arbitrary packs remain unverified.

The terrain G-buffer attachment at location 5 is now `gbuffer.material`,
RG32_UINT: float occlusion bits in x, exact material class in y. It replaces
R16F `gbuffer.occlusion`; regenerate experimental packs. Source and ray vertex
layouts remain 32 bytes. Attachment count remains seven with temporal inputs,
eight with hit-material diagnostics. The target costs six additional bytes per
world pixel versus R16F, approximately 9.375 MiB at 1707x960.

## Independent checks and retained failures

JAR SHA256 at the leaf checkpoint (later raw-indirect work is tracked separately
in INDIRECT_LIGHTING.md):
`DA77258ABD014A0E0AAB32A772C3F8287827147FC2447358B8A8ED950FD97509`.
Native SHA256:
`F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257`.

`qa-foliage-integrated-6` passes with alpha traversal, reflections, atmosphere,
camera-relative far coordinates, motion/jitter, actual NGX SR, resize/reload
and deferred retirement. Its foliage oracle independently intersects CPU source
triangles and original PNG texels, computes RGB transmission in double precision,
and compares actual GPU shadows and front/back leaf HDR. It does not reuse the
GPU traversal result as the reference.

| Coverage / maximum absolute error | Integrated run |
|---|---:|
| Compared samples | 2885 |
| Front / back lit leaf samples | 1050 / 262 |
| Partially transmitted / opaque blocked / open rays | 222 / 58 / 2605 |
| Shadow RGB error | 4.57573e-8 |
| Leaf HDR error | 1.18068e-4 |

Near source-texel boundaries are explicitly excluded by the CPU oracle.
Opposing closest faces are classified independently before checking unique-hit
Vulkan facing. Distance checks still cover these ambiguities; their tolerance
was not relaxed. In the final alpha run there are 11814 opposed closest-facing
ambiguities; 15663 raster/backface differences are separately verified, with
zero ambiguous cases contributing to that latter count. Maximum ray-distance
error is 4.46438e-5 blocks.

`qa-foliage-disabled-materials-1` passes the live atlas/material/reload path
with transmission off. `qa-foliage-surface-gpu-1` passes the world-free surface
decode diagnostic. These checks establish their stated contracts, not general
resource-pack compatibility, complete motion or cinematic quality.

Earlier runs remain in evidence:

- world-1/2 exposed nearest-crossing/reference disagreement at shared leaf faces.
- world-3/4 also record an incorrect intermediate CPU interpretation of Vulkan
  facing. Unique-hit comparisons disproved it; these are not all renderer bugs.
- world-5 is the first passing full leaf model, preceding final camera guards.
- natural-1 passed execution checks but visibly escaped its commanded camera.
  It cannot establish fixed-view image quality or paired performance cost.
- the intermediate missing-import compiler failure is preserved separately.

The final transport enumerates all accepted candidates; the CPU oracle uses
double triangle math, source PNGs and same-quad deduplication. The diagnostic
nearest-hit facing output does not control production transmission.

## Natural scene and next work

`qa-foliage-natural-2` passes at eight chunks, 1707x960 HDR reconstructed to
2560x1440. Each measured frame checks the actual shader camera eye and center
ray. Peak position error is 1.1921e-7 m; peak direction error 0.001077 radians
fits the projection jitter. Camera input locking only applies to the marked
QA world. Timing includes this CPU pose guard and no timed GPU readbacks.

| Wall intervals, 20 seconds per phase | Day | Sunset |
|---|---:|---:|
| Count | 7274 | 6141 |
| Mean | 2.74887 ms | 3.25677 ms |
| p95 | 3.4850 ms | 3.8706 ms |
| p99 | 5.3769 ms | 5.5027 ms |

Both windows report no throttle. Geometry changes during measurement; these
are wall-cadence observations of an unfinished renderer, not isolated GPU cost
or proof of the final 100 FPS product. Earlier generated worlds are not paired
A/B baselines. Raw day/sunset images and pack fingerprints are retained.

Canopy interiors remain too dark; water still has the legacy appearance.
Next visual work is indirect illumination with actual secondary visibility,
material-consistent hit lighting, temporal history rejection and filtering.
Water optics, clouds/weather/night, rough reflections and complete object motion
remain required. The Photon visual target is not accepted at this checkpoint.

Vulkan candidate traversal and facing contracts:
[ray traversal](https://docs.vulkan.org/spec/latest/chapters/raytraversal.html),
[instance flags](https://docs.vulkan.org/refpages/latest/refpages/source/VkGeometryInstanceFlagBitsKHR.html).
