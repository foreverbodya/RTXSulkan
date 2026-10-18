# Interior emitters and direct area sampling

## Visible defect and baseline

`qa-interior-1` captures the authored `authored-room-v1` at 1440p with native
NGX, traced diffuse paths and rough reflections. It completed successfully.
The actual image has warm fireplace light, very grainy floor highlights,
uneven wall illumination and a dark hanging lantern. It is a room with actual
Minecraft terrain, windows, beams, furniture and two glowstone fireplace blocks;
it is not a photometrically measured reference. The existing camera guards and
source geometry fingerprint are reused. No user's save or instance is edited.

The pack did not classify lanterns as emitters. `-EmissiveMaterials` now opts
into class 15 for the ordinary lantern: an authored warm/bright texture mask
selects luminous pixels and leaves dark metal non-emissive. This is not a PBR
emission map or calibrated power. Existing classes 9/10/11 retain their scales.
Raster and secondary-hit shading share `emitted-radiance.glsl`.

## Sampling change

`-EmissiveSampling 1` adds `EMISSIVE_SAMPLING=1`. It also enables the lantern
material. `-EmissiveComparison` generates an initial option 0 with the same
material mappings and runs 0/1/0 in one frozen room. Material identification
and estimator changes are therefore separated in that comparison.

The host collects nondegenerate accepted opaque/cutout triangles with material
9/10/11/15, retaining mesh/header/primitive and integer section coordinates.
Translucent triangles are excluded, consistently with the existing ray mask.
Per-mesh candidate lists are cached and pruned to the current scene. An
immutable 32-byte record per triangle is published with the TLAS generation;
its pointer/count use the three previously reserved `RaySceneFlags` words.
Previous lists retire with previous GPU readers. There is no per-frame world
scan or CPU copy of texture radiance. The bound is 262144 triangles / 8 MiB
per list. Overflow disables the whole proposal, preserving the original BSDF
emission estimator rather than silently excluding some lights from its PDF.

The shader selects a triangle uniformly and a point uniformly in its area.
It reads live atlas UV, tint, material and alpha. The solid-angle density is
`distance² / (triangleCount * triangleArea * abs(cosLight))`. Emission is
two-sided, matching the previous BSDF-hit convention. A closest terrain ray
must hit that exact sampled header/primitive; walls and alpha cutouts retain
their real geometry. Range and origin offsets retain the 128 m / .005 m policy.

One light sample competes with each ordinary diffuse cosine sample; four light
samples compete with the existing four GGX visible-normal samples on rough
specular receivers. Equal-count power-heuristic MIS weights partition the
emission term. The BSDF hit's solar/reflected term is not reweighted or sampled
a second time. Diffuse leaf vertices keep their original lobe sampling and
emission estimate; the new diffuse NEE is not applied to those vertices.
There is no radiance clamp or reduction of the existing bounce/sample counts.
This adds work to reduce small-source variance; it is not an FPS optimization.

The implementation is authored from the rendering equations, not copied from
sample code. Primary references: PBRT fourth edition
[area lights](https://pbr-book.org/4ed/Light_Sources/Area_Lights),
[simple path tracing](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Simple_Path_Tracer),
and [MIS path tracing](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).

## Acceptance scope

`qa-emissive-comparison-1` completed the same-room 0/1/0 run successfully with
runtime `64ED40309EDED10C4888954D438982C054AA5C9C922AB633A0477685CA70753F`.
The actual inverse view, pose, unrelated options and geometry digest
`8b5b4382ad7063bb2639d377d89bc50988695c9e8527d94025ed7625aff63f62`
match across all three captures. Native images were inspected: fewer bright
specks on walls/furniture and more continuous warm light; floor highlights
remain grainy and luminous sources clip. The user explicitly reported severe
dynamic noise, so the next change must address filtering and actual frame
sequences rather than interpreting these stills as temporal acceptance.

Proposal count is 0 / 708 / 0, with 22656 bytes in the active list. Wall cadence
is 24.44 / 39.90 / 23.90 ms; diffuse trace is 18.83 / 32.67 / 17.60 ms and rough
trace is 1.69 / 3.86 / 1.32 ms. It is more expensive. Whole reconstructed HDR
mean luminance is .007415 / .007433 / .007425; EV is 4.564 / 4.513 / 4.567.
Single-frame high-frequency residual RMS decreases in fixed wall/floor crops,
but that metric includes textures and edges and is not temporal variance or an
independent physical reference. Exact values and capture hashes are in
`evidence/emissive-analysis-1/analysis.json`.

Static execution, finite output and exact
geometry identity will not prove source-transport convergence, temporal image
quality, lamp photometry, emitter removal, all-weather behavior or artistic
acceptance. The material and sampling options are experimental and not promoted
to the user's installed preset.
