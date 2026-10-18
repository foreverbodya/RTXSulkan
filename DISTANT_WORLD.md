# Distant world and visible horizon

## Cloud target correction (supersedes the old cloudy lookup below)

The initial operator projected the whole cloud-environment horizon into the
lower hemisphere. An interior-cloud scene exposed radial spokes despite its
arithmetic checks passing. V3 instead preserves the actual screen cloud C/T
and clamps only the clear background; see CLOUD_INTERIOR.md for the cause,
six actual old/new/old captures, independent replay and remaining limits.
V1/V2 records below describe their original operator and are preserved as
historical evidence, not acceptance of the old cloud projection.

The experimental deferred lighting omitted the engine's render-distance fade.
Its sky environment also contains an average planet ground below the spherical
horizon. Displaying that environment beyond the loaded Minecraft terrain creates
a grey/brown band. A real frozen day frame records 15,510 missing-world pixels
looking toward that planet, 5,607 terrain pixels beyond render-fog end and 3,727
inside its transition. The visible sky agrees with its captured LUT; the source
is established in horizon-source-inspection-1.json.

## Optional operator

`-DistantWorld` adds a pass after water/cloud composition and before existing
reconstruction/display. Atmosphere is required; DLSS is not. Packs without this
switch retain their existing graph. `DISTANT_WORLD_BLEND` selects copy (0) or
enabled (1). The user instance is unchanged.

Recognized terrain and traced water use camera-relative
`max(length(xz), abs(y))` and the engine's render-distance start/end for a linear
visibility fade. Strength follows existing atmosphere activation. Fallback
surfaces are copied. The target samples the atmosphere/cloud environment, with
downward lookup directions extended to the horizontal sky. The solar disk keeps
its actual direction/cloud transmission. Missing world below the horizon uses
the same extension. Lighting/reflection environment LUTs are unchanged.

This is a loaded-world boundary operator, not extra physical extinction, distant
geometry, solved terrain LOD or full environmental/status-effect fog. Existing
primary/cloud depth and motion remain in use; mixed background/surface motion
and disocclusion need further evaluation. Zero-weight HDR pixels copy byte for
byte. Final display pixels may still change through exposure/reconstruction.

## Static evidence

qa-distant-world-comparison-2 passes six frozen 0/1/0 day/sunset captures on host
38EE8074D8E3A6F6460FC4781BEED7F6CA3190927DC4D228602E30B7B87AC9C4.
Geometry and inverse-view matrices match exactly; jitter/ray seeds differ.
Geometry SHA256: 5057e74b5cc2895436794748f08275ee498f4c76503cb4b3036651ff97c7e8d5.
Actual screenshots show the band removed and distant trees blending into sky.
This is a component improvement, not whole-image cinematic acceptance.

| Enabled capture | Day | Sunset |
| --- | ---: | ---: |
| Changed HDR pixels | 29,170 | 29,235 |
| Byte-exact copied pixels | 1,609,550 | 1,609,485 |
| Max range-weight error | 0.0000018341 | 0.0000018834 |
| Max distance error, m | 0.00002961 | 0.00002537 |
| Max FP32 blend error | 0.00000002772 | 0.000000006943 |

Each disabled capture copies all 1,638,720 RGBA pixels exactly. Six offline
replays pass; seven actual-buffer corruption mutants reject. See
distant-world-comparison-analysis-1 for images/options/input hashes. Diagnostic
source/guide/target/FP32 images are opt-in; production adds only its HDR result.

## Preserved format-checker failure and policy revision

qa-distant-world-comparison-1 fails at pixel 889298 under the original
`0.00001 + 0.00055*abs(expected)` half-output budget. Red 0.09051513671875 is
the lower half neighbor of a blend near 0.0905758993; nearest half would be
0.090576171875. The first capture lacks a preconversion readback. Both old
replays still reject it with its original budget; it is not retroactively green.

Finite floating-point image conversion permits either adjacent representable
value without fixing a rounding direction. See pinned
[Vulkan 1.4.329 fundamentals](https://github.com/KhronosGroup/Vulkan-Docs/blob/v1.4.329/chapters/fundamentals.adoc)
and [image write encoding](https://github.com/KhronosGroup/Vulkan-Docs/blob/v1.4.329/chapters/images.adoc).
Source hashes are in distant-world-format-research-1.json.

Protocol v2 separately records the actual FP32 blend. Its arithmetic budget is
`2e-7 + 2e-6*abs(expected)` after independent source/weight/target checks. Each
changed RGB FP16 store must be one of that FP32 operand's immediate neighbors;
an exactly representable half has one accepted encoding. The interpolation
formula is unchanged, with a precise intermediate for readback. No pack/unpack
forces a preferred rounding mode and no generic HDR epsilon is widened.
The helper checks all 63,488 finite signed half encodings and 190,458 interval
cases; overflow/NaN behavior and arbitrary shader arithmetic are outside scope.

The combined cloudy/moving run qa-distant-world-cloud-motion-1 passes on the
same host. Its continuous/adjacent evidence is separate from temporal aesthetic
acceptance: 601 path frames, 300 translations/yaws in each direction, 24
consecutive readbacks, all 23 history/matrix relationships valid. Every burst
frame binds distant.world.hdr as the actual native reconstruction color input.
The cloudy day/motion/sunset operator captures independently replay too.
Local clean runtime/sources/native artifacts reproduce byte for byte, and
independent generation reproduces all 106 tested pack files. These are local
checks, not independent-checkout or release acceptance.
Wider sun/view angles, immersion/night/weather, status effects,
longer gameplay and environment approximation remain open. The earlier water
precision failures and full sweep retain their unchanged limits. Source
provenance, licensing and release acceptance are still open.
