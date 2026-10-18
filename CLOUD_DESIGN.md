# Cinematic cloud volume

Status: an opt-in static HDR cloud preview executes in day/sunset captures.
Density and composition checks pass; the new silhouette is less rectangular,
but the inspected clouds remain smooth/blurred and fail cinematic acceptance.
Static world cloud shadows and receiver projection pass source-based checks.
Moving guides and temporal stability remain
unfinished. There is no FPS target.

## Reuse and representation

The official Sulkan 0.5.1 bundled pack supplies cloud-field.glsl (continuous
vanilla-layout sampling), cloud-volume.glsl (front-to-back volume integration)
and clouds.fsh (view/depth output). Its old four-block layer and palette-based
lighting do not directly establish the cinematic HDR result. Reuse the layout,
periodic coordinates and integration structure; keep the shared renderer and
other mod binaries intact. Retain the project's GPL attribution/provenance.

The new field keeps the original alpha occupancy cutoff and smooth cell-edge
reconstruction, with source RGB decoded to linear before interpolation. A
48-block cell size expands layout features and the vanilla offset is scaled by
48/12, preserving the native clock's wrap period. The current 128-block layer
uses a height-dependent threshold and a periodic cellular volume inside soft
lower/upper boundaries. Layout coverage controls a weather threshold rather
than a fixed extruded top surface. The previous value-noise profile used
64/32/16-block scales with weights 0.625/0.25/0.125; it remains replayable.
Extinction is bounded at 0.045 per block. These are explicit artistic parameters,
not measured cloud microphysics. Every scale uses an integer lattice count per
layout period, retaining periodicity for rectangular layouts. Noise fractions
at the added scales remain separate from large integer lattice coordinates.
Historical profiles remain in the replay-only CPU reference; metadata distinguishes
`legacy_layer_v1`, `billow_fbm_v1` and current `cellular_atlas_v1` readbacks.

## Required integration

1. Independently check density, color, signed wrapping, vertical bounds and
   period seams against the original cloud PNG and double arithmetic.
2. Integrate premultiplied HDR scattering and transmittance through the field,
   using the same atmospheric solar/sky inputs as terrain. Keep source radiance
   and exposure separate. A named ambient/multiple-scattering approximation must
   be documented rather than silently copying the old palette multiplier.
3. Provide a world-periodic solar-transmittance map. Its sampling coordinates
   follow each receiver's world position and height; camera movement must not
   move shadows across static geometry. Apply it consistently to primary and
   secondary direct illumination, reflection hits and water hits.
4. Composite bounded view radiance before display/reconstruction. Terrain limits
   each view segment. Attenuate the solar disk through the same transmittance.
   Feed a clouded environment consistently to ray misses and diffuse sky input.
5. Supply finite cloud optical-depth/motion guides. Advect previous positions
   with the same wrapped phase used by density, include camera translation and
   rotation, and distinguish mixed sky/cloud pixels. A volumetric proxy depth
   is not an exact surface depth. Review temporal illumination history as clouds
   move; rotation-only sky vectors alone are insufficient for finite clouds.
6. Check source-based numerical fixtures, period/far-coordinate/camera cases,
   reload/resize/retirement and actual day/sunset/moving gameplay images. Preserve
   any failure. No FPS goal applies; optimize only after visual acceptance.

## Current preview and evidence (2026-10-16)

The preview pins cloud phase to zero. It integrates a half-resolution cloud view
bounded by the nearest world/water surface, composites premultiplied radiance
and transmittance into HDR after water and before reconstruction/exposure, and
feeds a camera-origin clouded environment to ray misses and sky SH. The visible
water solar disk uses the cloud environment's transmittance. A 512x512 fixed
world-period map now attenuates solar lighting at primary/secondary receivers,
reflection hits, water entry and submerged hits (sampled at the air entry).
Below the volume, receivers project along the sun to the cloud-bottom plane;
inside the layer they integrate directly, and above it they bypass attenuation.
The map uses 64 midpoint samples over at most 16,384 blocks. Its continuous
integral convergence and moving-camera behavior remain unverified. Cloud shadows
do not yet affect atmospheric aerial in-scattering along the camera path.

Lighting uses a dual Henyey-Greenstein phase function and three approximate
light-ray scattering terms. Each successive term reduces contribution, optical
depth and phase eccentricity by 0.5. View extinction/composite alpha stay those
of the original field: this is not the full multi-order integral described in
[Hillaire's Frostbite notes, section 5.8](https://media.contentapi.ea.com/content/dam/eacom/frostbite/files/s2016-pbs-frostbite-sky-clouds-new.pdf).
It is not solved or proven energy-conserving multiple scattering. Ambient
remains an explicit isotropic clear-sky SH closure. Cloud solar quadrature now
targets at most 4-block steps, with 24 minimum and 512 maximum samples; long
paths can hit that cap. Its independent scattering oracle remains open.
The earlier scattering checkpoint is recorded in `evidence/cloud-scattering-reference.json`.
The later cellular adaptation is recorded separately below. The environment is a camera-origin approximation without
per-hit cloud parallax. Bilinear cloud upsampling has a foreground slab guard,
not complete depth-aware reconstruction around elevated geometry. Finite optical
proxy values do not establish correct moving depth/motion guides.

- `qa-cloud-field-3`: source-PNG/double checks pass for 8,192 points per phase,
  including exact signed-wrap/hash probes, layer bounds and periodicity. The
  oracle evaluates actual captured Float32 translated coordinates; a separate
  double-precision check retains mathematical periodicity. Tolerances were not
  increased after the earlier failures.
- `qa-cloud-static-preview-1`: static day/sunset execution, finite HDR values,
  bilinear coefficients and composition pass. Day composition compares 13,485
  samples with maximum error 0.000243272. These checks do not independently
  validate scattering or cloud occlusion. The camera pitch is -10 degrees;
  older forest captures use +15 degrees and are not matched-view comparisons.
- `qa-cloud-disabled-regression-1`: the full existing integrated fixture passes
  with the preview disabled after shared wrapping/atmosphere/water changes.
  All three non-Sulkan mod hashes remain unchanged.

The first preview and disabled regression above use host SHA-256
`1B4C750D5016F6DDB6165166EADDED67583B7710074EBE814E0DE62BC7499F3B`.
Failed field runs 1 and 2 and their replay limitations are retained. Run 2 exposed
ill-conditioned straight RGB in empty cells and translation input rounding;
the current field uses premultiplied linear color and cell-local coordinates.
The legacy negative-remainder counterexample remains diagnostic-only.

Original Minecraft cloud PNGs retained for local replay are ignored by Git and
must not enter source/release archives. Bundled source hashes and reuse scope
are recorded in `evidence/cloud-bundled-source-reference.json`.

## Shape and world-shadow checkpoint

- `qa-cloud-billow-preview-1` failed the unchanged translated-density tolerance:
  pixel 1522 differed by 0.00000353918. Its original inputs still replay that
  one failure. Splitting added noise scales into integer lattice and fractional
  coordinates corrected the precision loss without widening the tolerance.
- `qa-cloud-billow-preview-2` passes source density/period checks and static
  day/sunset composition. Maximum translated-density error is 0.00000103865.
  The old field failure and old successful static preview also replay with
  their historical model, rather than being interpreted as the new density.
- `qa-cloud-shadow-preview-1` passes 306 source-PNG/double map texels per phase.
  It does not yet include the later receiver diagnostic.
- `qa-cloud-shadow-receivers-1` fails the strict visibility range: periodic
  bilinear summation returned 1.000000119 at pixel 5758. The failure is preserved
  in raw buffers and `cloud-shadow-bilinear-endpoint-failure.json`. The shader
  now clamps this probability to [0,1]; HDR radiance and QA limits were unchanged.
- `qa-cloud-shadow-receivers-2` passes day/sunset source-map, density, composition
  and 8,192 receiver cases per phase. Below/inside/above counts are 2,752/2,752/
  2,688. Maximum map-source errors are 0.0000219074 / 0.0000169371; maximum
  receiver errors are 0.0000724298 / 0.000122809. CPU exact-period errors stay
  below 1.4e-13. Captured actual Float32 translations are checked separately.

The latest receiver run uses host SHA-256
`BB351A17DFC3459B67228667C8F4EE23B4FBF4A5269B36AAB03AACC0ABA5EE5C`.
The failed receiver run uses the same host but different external GLSL. Each
archive includes generated pack hashes; the host hash alone does not identify
the rendered shader version. Raw map/receivers/positions/frame and original PNG
are saved before numerical assertions, allowing failures to be investigated.

The newer sunset solar integration uses shorter light steps; inspection shows
less horizontal banding than the earlier 24-step source. Shape and light are
still too smooth to accept the cinematic result. There is no matched-geometry
image attribution or independent multiple-scattering/convergence claim.

`qa-cloud-billow-disabled-regression-1` passes the full existing integrated
fixture on the same BB351A17 host with unchanged non-Sulkan mod hashes. This
retains source-based transport, temporal/filtering, water, motion and lifecycle
gates with the cloud preview disabled; it does not close the older intermittent
water/secondary-ray failures documented elsewhere.

Two local offline clean builds reproduce runtime JAR, sources JAR and native
DLL hashes (`evidence/cloud-volume-clean-rebuild.json`). The first attempted
pre-clean sources lookup used a missing filename; sources repeatability is
instead established by comparing the two subsequent successful clean builds.
The runtime JAR/native DLL also match the pre-clean tested artifacts. Generated
pack contents initially differed only in JSON key order across processes,
with semantic equality. The generator now sorts object keys ordinally and
preserves all arrays; two separate processes produce 92 byte-identical files.
This remains local reproduction, not an independently provisioned checkout.

`qa-cloud-shadow-canonical-1` then passes the complete static day/sunset preview
checks after canonical serialization, using the clean rebuilt host/native pair.
All 92 generated files match the separately regenerated pack byte for byte.
Current checked images are in that evidence directory. The code/source
checkpoint is `evidence/sulkan-source-checkpoint-20261016-cloud-volume-v1.zip`;
its external per-file manifest verifies source contents and excludes evidence,
playtests, build caches, Minecraft replay PNGs and NVIDIA SDK/runtime binaries.
This is an internal checkpoint, not a finished release.

## Cellular density and raw HDR inspection

`qa-cloud-raw-reference-1` captures the previous profile's raw view, geometry,
environment, lighting and composition inputs before validation. Broad smooth
regions already exist before SR; this supports changing density/lighting, but
does not establish that SR contributes nothing to final-image blur. Subsequent
captures preserve these raw buffers and frame metadata for replay.

The current asset adapts the nearest-cell structure from MIT
[TileableVolumeNoise](https://github.com/sebh/TileableVolumeNoise/tree/1f879a26b89aaa4b5a7f112f14ec149d08057661).
The generator uses local integer hashes, independent XYZ feature offsets, explicit
UNORM rounding and a 128^3 RGBA slice atlas. GLM and LibTarga are not imported.
Copyright/license text is retained in the host and generated pack. Source/license
hashes and exact reuse are in `evidence/cloud-cellular-reference.json`.

The atlas contains inverted clamped squared nearest-feature distances at 8/16/32
cells per tile in RGB, and 8-cell periodic value noise in A. The 512-block nominal
tile is fitted to an integer repeat count per weather period. Eight explicit
texel fetches implement periodic 3D trilinear sampling at voxel centers, including
slice boundaries. Shape is `0.70 R + 0.20 G + 0.10 A - 0.14 (1-B)` with threshold
`0.48 + 0.22 height^2 + 0.50 (1-coverage)`; residual density uses gain 4 and the
existing vertical envelope/extinction bound. These remain artistic parameters.

`tools/assets/Generate-CloudNoise.py` regenerates all pixels and verifies 528
distinct voxel/channel cases with independent scalar integer/double arithmetic.
A separate generation produces identical PNG bytes, SHA-256
`dd31ea38e17a162cff0f87837a7126f3cbf04dadc1a7c3a9d1585d53925f2ecc`.
The renderer's QA-only source API returns the actual loaded texture bytes for a
scheduled pass/slot; validation does not read an assumed asset path. GPU density
and interpolation compare against that PNG. Copies are owned procedural noise,
distinct from the ignored Minecraft weather PNG.

`qa-cloud-cellular-1` fails four translated-density cases, max error 0.00000361037.
Its original buffers still reproduce all four failures. `precise` split-lattice
intermediates preserve the intended operation order in the sampler; the next run
also captures both translated noise samples directly. `qa-cloud-cellular-2`
passes day/sunset density, 24,576 cellular samples per phase, composition and
shadow/receiver checks without increasing tolerances. Maximum density error is
0.0000000417357, translated-density error 0.0000000378896 and cellular-channel
error 0.000000200481. Original failure and corrected run have separate archives.
Legacy model replays remain versioned; PNG hashes are checked before replay.

Inspected day/sunset images have a more broken silhouette and cellular lobes.
The light remains too uniform and the result still fails cinematic acceptance.
Static preview phase remains pinned to zero; no moving-cloud quality is claimed.
The optional `-CloudCameraAudit` adds settled translation, rotation and return
views with unchanged daylight. Its world shadow map comparison is byte-exact;
this is an anchoring check, not a continuous motion/ghosting or cloud-guide test.

`qa-cloud-camera-audit-1` passes all five captures (day, translation, rotation,
return, sunset) on host `9796A94915C6E09D60DB6750DF05F6D5286483CCC51B58DB60683BB5C7784476`.
Actual shader eye/direction are checked every measured frame. Independent raw
file comparison confirms byte-identical world shadow maps and fixed-world density
probes across all four daylight poses. The environment is identical under pure
camera rotation at the translated position and identical again on return to the
initial position. See `evidence/cloud-camera-anchor-analysis.json`. Geometry
changes between views are recorded; no matched-geometry image attribution is made.

The earlier cloud-disabled run `qa-cloud-cellular-disabled-1` passes on host
4076726F…DC1B96A1. The latest host's `qa-cloud-cellular-disabled-2` **fails** the
retained water primary/source boundary case at pixel 313740 (1359,183). Double
replay still has no strict source hit: minimum barycentric -0.00000873067,
projected edge distance 0.000620761 pixels. Nearest vertex rounding at the earlier
observed 8 subpixel bits covers the pixel, but does not prove actual GPU rounding.
No tolerance, exclusion or coverage acceptance has been changed. The original
failed report/case and replay are archived; this failure remains open.
Comparison with qa-atmosphere-multiple-2 confirms identical pixel, camera,
current matrices, water depth/normal and closest candidate triangle; the complete
source triangle lists differ. See cloud-cellular-water-historical-comparison.json.

The clean rebuilt runtime JAR, sources JAR and native DLL match the tested hashes.
All 96 shader-pack files reproduce byte for byte. The generated noise asset also
reproduces byte for byte. `evidence/cloud-cellular-clean-rebuild.json` records the
successful scoped cloud checks and the failed latest regression separately.
The internal source checkpoint is
`evidence/sulkan-source-checkpoint-20261016-cloud-cellular-v1.zip`, with a verified
per-file manifest. It excludes evidence, caches/playtests, the original Minecraft
weather PNG and NVIDIA SDK/runtime binaries. It is not a finished release or
independent-checkout reproduction.

Next: improve internal lighting and resolve the water raster/source boundary gate,
and establish moving-cloud/camera contracts. Reassess actual imagery
at each stage; numerical passes alone do not accept the visual result. Further
DLSS work and performance optimization follow visual acceptance.

## Per-order view transport revision

The next model `three_orders_view_and_light_v1` integrates separate camera-ray
transmission for all three solar orders. Extinction and scattering scale by
1, 0.5 and 0.25, so their ratio remains one; phase eccentricity scales likewise.
Order zero alone determines composite transmission and optical proxy. Ambient
remains the previous isotropic clear-sky SH closure. Atmospheric attenuation
applies to summed radiance; additive aerial light is weighted once by physical
order-zero opacity, avoiding duplication for artificial scattering orders.
The shared helper uses a small-optical-depth series for stable step opacity.

The uniform-slab GPU probe writes three order radiances, transmission and actual
input parameters before checks. The Java oracle uses a closed-form integral,
including zero density and the equal-view/sun-angle limit. Its error budget
separates the analytic bias of midpoint solar sampling from FP32 accumulation.
Independent Python replay uses a difference-of-exponentials expression. This
does not establish convergence in heterogeneous density or exact multiple
scattering. Provenance is in cloud-order-scattering-reference.json.

The first probe run `qa-cloud-order-view-1` fails its declared parameter grid:
GPU exp2 produces 4.000002861 rather than 4 at 256 cases. All 6,144 order
radiances pass offline against actual captured inputs, but the overall failed
run stays failed. The next probe uses explicit binary-exact column values and
keeps original numerical budgets. This is a fixture input correction, not a
cloud-density change or relaxed validation limit.

The preceding water policy is now revised separately in WATER_COVERAGE.md.
Its historical failure remains archived; its new uncertain-plane GPU path has
not yet been exercised. This diagnostic change makes no visual-fix claim.

`qa-cloud-order-view-2` passes day and sunset on host
51BF5D1B75BBB81E531178B4AD1F30A9E605CC3FFE0CAC7735ADD4A5C2B43F30.
Each phase checks 2,048 analytic slabs / 6,144 order radiances, including 256
empty and 256 equal-angle slabs. Maximum absolute radiance error is
0.0000150464; base transmission error is 0.0000157355. Independent Python replay
agrees. Three intentionally corrupted copies (radiance gain, negative
transmission, wrong input) are rejected; original captures are unchanged.
Existing source-PNG field, receiver shadow and HDR composition gates also pass.
The Khronos Apache license/notice bytes are verified inside the actual JAR.

Both images were inspected. Clouds remain too soft and uniformly lit for the
requested look; this is **not cinematic acceptance** or a demonstrated large
visual improvement. Previous and current forests have different source geometry,
so the screenshots are not a controlled whole-scene A/B comparison. The field is
static, and continuous movement/proxy vectors remain open. Next image work must
address internal light/ambient occlusion and density detail with actual raw
frame comparisons, rather than treating successful analytic tests as beauty.

## Local ambient column visibility

`vertical_columns_isotropic_hemispheres_v1` replaces the uniform local ambient
source with separate upper/lower column attenuation. Each vertical optical
depth integrates the actual density with 32 midpoint samples. Under a local
plane-parallel assumption, mean isotropic hemisphere transmission is
`integral_0^1 exp(-tau/mu) dmu = E2(tau)`; eight-point Gauss-Legendre quadrature
approximates it. The clear-sky upper/lower irradiances divided by 2*pi are
weighted separately. Ambient remains order zero. This closure misses lateral
escape and angular sky detail; it is not a full 3D ambient transport solution.
Primary mathematical reference and exact reuse are in cloud-ambient-reference.json.

qa-cloud-ambient-comparison-1 passes six 0/1/0 captures (day and sunset) on
B209B62C0D8D9DA0192D719B15BA216B85F5C409295D1E2428D2DFCA5CCAA1E7.
The active option and frozen source geometry are checked through every trial.
Cloud field, shadow/receiver, composition and scattering checks pass at each
capture; raw buffers have distinct names, so later trials do not overwrite
the baseline. Each capture checks 252 source optical columns and 16,384 GPU
hemisphere evaluations. Source-column maximum error is 0.00000139707 and the
GPU angular-formula error is 0.000000144313. Continuous angular approximation
error is reported separately: 0.00313342 absolute transmission, confirmed with
both Java E1-series/recurrence and independent Python adaptive integration.
It is not hidden inside the 0.000002 GPU formula budget.

Inspected sunset captures show clearer dark/lit separation; daytime change is
modest. Clouds remain too smooth/soft, so cinematic acceptance is still false.
Autoexposure remains active and can compensate some brightness changes; raw
HDR is retained for evaluating the transport independently. Neither a settled
comparison nor its PASS proves continuous camera/cloud motion or final quality.

## Detail erosion profile

`CLOUD_DENSITY_PROFILE=1` selects `cellular_detail_v1`; default 0 preserves
`cellular_atlas_v1`. The 128^3 owned noise PNG is unchanged. The same atlas is
also sampled at a nominal 128 m tile, fitted to integer weather-period repeats,
with explicit periodic trilinear fetches and split-lattice arithmetic. RGB
detail wavelengths are approximately 16/8/4 m. The artistic erosion amount is
`0.22 * (1 - dot(detail.rgb, (0.625,0.25,0.125)))`; base shape remains
`0.70 R + 0.20 G + 0.10 A`. The existing weather/height threshold is retained,
and density residual gain is 6 rather than 4. This is a volume-density change,
so view light, ambient columns and world cloud shadows see the same field.

qa-cloud-detail-comparison-1 passes six frozen-source 0/1/0 day/sunset captures
on host 4F1D2A4307B686E3B0CF68CCA6981A9AD932017CD7B096D2077CE226A1CA1CF1.
The model version is recorded in every field/shadow capture. Each capture keeps
14 field buffers, including 24,576 direct base-atlas and 24,576 detail-atlas
evaluations at original and translated positions. Maximum profile-1 density
error is 0.0000000594046, translated density error 0.0000000585243 and combined
atlas error 0.000000201140. Existing numeric limits remain unchanged.
Independent field replay passes on day/sunset; legacy failing input still has
its original four translated-density failures, and the previous passing input
still has zero failures.

Independent file comparison confirms exact source geometry across all trials,
active profiles 0/1/0, and byte-identical density probes and world shadow maps
on return to profile 0. Candidate maps differ as expected. Raw view HDR changes
more than baseline-return jitter variation; this is not a convergence bound.
See cloud-detail-comparison-analysis.json. The static day/sunset images show
more broken silhouettes and smaller lobes, but remain too soft for the complete
requested look. Dynamic phase, moving cloud proxy guides and temporal stability
remain unproven. No new DLSS feature or performance target is introduced.

This static visual run does not close the separate grazing-water regression or
the incomplete 64-phase motion sweep. The user instance remains unchanged.

The runtime JAR, sources JAR and native DLL reproduce byte for byte after a
local clean rebuild (cloud-detail-clean-rebuild.json). An independent pack
generation reproduces all 99 files (cloud-detail-pack-reproduction.json).
These are local reproducibility checks, not independent-checkout or release
proof. Current full synthetic regression was not rerun on this host; its last
grazing-water failure remains open with exact retained inputs.

## Finite cloud reconstruction guides

The existing native reconstruction path now has an opt-in finite cloud proxy
depth/motion pass, leaving the surface inputs to GI unchanged. Shared integer
pixel-to-half-resolution sampling corrects an actual retained 0.0136135 m
proxy-interpolation failure without increasing the numeric budget.
The continuous camera audit passes 406 rendered frames with both translation
and yaw directions and four saved moving guide/HDR/display captures. All seven
guide captures replay independently; option-0 day/sunset copies all 1638720
pixels' base guides exactly. See CLOUD_RECONSTRUCTION.md for exact model,
current runtime hash, preserved first failure, negative cases and limitations.

This verifies finite-proxy arithmetic in the existing image path. The dominant
opacity proxy is approximate, its threshold can cause transitions, and four
separated frames do not establish temporal quality. Cloud advection, physical
multilayer flow, controlled adjacent-frame A/B and finer-density ray-march
convergence remain open. Default guides remain disabled and the installed
user preview is unchanged. No new DLSS features or performance target are added.

Refined view-density integration is now an optional separate quality experiment:
up to 32 <=1m density substeps per existing lighting interval, coherent optical
column and opacity centroid for the three-order weights/color/proxy. Six frozen
0/1/0 day/sunset captures, local GPU source/math checks and replay pass. On a fixed
24-ray set, transmission discrepancy from a 0.125m density grid reduces from
0.01314 to 0.000467 and proxy discrepancy from 34.18m to 0.212m. This is not a
proof of continuous-integral or radiance convergence. Actual visual change is
modest and the default remains off. See CLOUD_SAMPLING.md for full data, retained
graph-integration failures and remaining motion/appearance/release gates.
