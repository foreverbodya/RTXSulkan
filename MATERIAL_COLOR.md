# Base tint and baked vertex occlusion

2026-10-15. Material-input correction for the cinematic renderer; not complete
indirect lighting, foliage transmission or a finished visual pack.

Historical checkpoint: the source/ray vertex contracts below remain current,
but the foliage stage replaces the R16F occlusion target with RG32_UINT
`gbuffer.material` (float AO bits, uint material class); see
[FOLIAGE_TRANSMISSION.md](FOLIAGE_TRANSMISSION.md). Its cost is six extra bytes
per world pixel versus this checkpoint, with no extra attachment. Earlier natural
captures did not verify actual camera pose; they are observational rather than
verified fixed-view comparisons. Raw evidence is retained unchanged.

## Reason

Inspection of the pinned Sodium 0.9.2 bytecode confirms that BlockRenderer gives
ChunkVertexEncoder.Vertex separate `color` and `ao` values. CompactChunkVertex
multiplies the RGB bytes by `ao` when writing `a_Color`. Previously Sulkan used
that baked result as material color, including for sunlight and emission. The
new path preserves both original inputs at Sulkan's existing encoder hook.
It does not divide an already quantized color to guess the original tint.
Sodium's `ao` comes from its brightness data and is not a physically measured
hemisphere visibility; it remains a legacy ambient-light approximation.

## Source vertex contract

Active Sulkan material meshes now use 32 bytes per vertex. Original compact
fields and the existing material/wind word retain their byte offsets:

| Byte offset | Value |
|---|---|
| 0, 4 | Packed position high/low |
| 8 | Original baked `a_Color`, unchanged from Sodium's compact encoder |
| 12 | Packed texture coordinate |
| 16 | Light, terrain flags and section index |
| 20 | Sulkan material and wind |
| 24 | Original pre-AO color as RGBA8_UNORM |
| 28 | Original float brightness/AO |

Builtin/legacy raster shaders continue reading their original `a_Color`.
The new experimental terrain vertex shader additionally exports base tint and
occlusion. Accepted CPU mesh snapshots own all fields independently of upload
buffer lifetime. Legacy 20/24-byte snapshots remain decodable: their pre-AO
color cannot be recovered, so they retain baked color with occlusion 1.

The ray payload stays 32 bytes per vertex: XYZ at byte 0, occlusion in the
previous padding float at byte 12, base tint at byte 16, then UV/light/material.
BLAS ignores the fourth position component, preserving its position layout.
`SulkanRaySurface.color` and CPU `Surface.tint` now describe pre-AO tint for
32-byte source meshes; the separate `occlusion` field is barycentrically
interpolated. Older raw attribute readers must account for this semantic change.
Using the helper alone does not unbake old 20/24-byte source colors.

## Lighting and resources

The generated ray-lighting pack writes unshaded linear material RGB and a
separate R16F `gbuffer.occlusion`. Baked occlusion multiplies approximate sky
and block-light fill once, in linear light. It does not attenuate the direct
sun BRDF or material emission. Reflected hit shading follows the same rule.
Cutout texture/vertex alpha and thresholds are unchanged. This does not replace
vanilla skylight, provide traced indirect illumination or calibrate materials.

The original baked main-color output and translucent fallback remain in use
for legacy rendering. The pack's terrain-material vertex shader is derived from
the GPL Sulkan Realistic terrain shader in the official bundled artifact.
No other mod binary is modified. Old generated experimental packs should be
regenerated alongside the new host; their baked-color assumptions differ.

AO adds one color attachment. Normal temporal rendering uses seven total color
attachments; material diagnostics use eight. Hit-material and temporal-input
diagnostics therefore run separately, with an explicit generator rejection of
their combined nine-attachment configuration.

Source/raster vertex storage increases by eight bytes per vertex, and the AO
target adds two bytes per world pixel. The ray attribute payload does not grow.
This is not a claim that total VRAM remains unchanged.

## Executed evidence

Canonical tested JAR:
`F353604B93C5D7C4BD0B0200F657A6E8F710AE1F235F59E6A662A28C80BD84EE`.
Exact generated pack inputs are recorded separately in each archived run.
Clean local offline JAR and native rebuilds match the tested hashes;
`evidence/material-color-build-repeat.json` links their logs and the final CPU
contract. This does not prove independent-checkout/machine reproducibility.

- CPU contract: source strides 20/24/32, independent barycentrics, different
  baked/base colors and nonuniform AO, unchanged serialized size, invalid AO
  rejection and owned-buffer lifetime.
- Actual Sodium encoder check: source color/AO preserved exactly, original baked
  colors match CompactChunkVertex, material IDs survive, 128-byte quad result and
  end canary are correct after original buffers are overwritten.
- `qa-separated-material-gpu-2`: 18 real rays over three source formats, sampling
  the second payload page, with distinct color/AO interpolation and zero leaked
  native buffers. Missing writes retain NaN sentinels and fail the check.
- `qa-separated-materials-world-3`: 657 live-atlas samples before and after reload;
  138 have non-unity AO. Maximum albedo difference 0, tint difference 1.1921e-7,
  UV difference 0.00778962 atlas texels, distance difference 0.000610352, and AO
  difference 0.00048828125. AO compares ray, raster-reference and actual AO target.
  Color comparison retains the prior nearest-texel-boundary policy; AO does not
  exclude those boundaries. Existing numeric tolerances were not loosened.
- `qa-separated-materials-integrated-1`: alpha traversal, all 32 jitter phases,
  reflection/removal, +/-16M coordinates, actual DLSS, resize/reload and deferred
  retirement pass. Minimum non-unity-AO coverage across phases is 539 samples;
  maximum lighting error is 0.00048830595 against double CPU BRDF/aerial math.

Earlier `qa-separated-materials-world-1` failed because the old diagnostic treated
hit albedo alpha equal to 1 as its hit sentinel. Alpha now carries AO. The check
uses positive traced distance, still independently comparing that distance and
material/primitive/address metadata. The failure is preserved. Run 2 passes the
corrected sentinel; run 3 additionally requires non-unity AO coverage and checks
the dedicated target. GPU run 1 and world run 2 retain their earlier JAR hashes.

## Natural-world result

`qa-separated-materials-natural-1` repeats the forest/river recipe at eight chunks
with 1707x960 input and 2560x1440 DLSS Quality output. Day and sunset HDR/output
checks pass. Unthrottled wall intervals average 2.3804/2.4467 ms, p95
2.9802/3.0182 ms. Geometry changes during the intervals; these are observations
of the unfinished effect set, not isolated GPU costs or final 100 FPS acceptance.

Sunlit surfaces are visibly lighter, while canopy interiors remain excessively
dark and water still follows its legacy path. The cinematic visual target remains
unmet. Fresh generated scenes differ in vegetation details; the same seed and
commanded camera do not make these pixel-identical A/B captures. No numerical
image-difference or reconstruction-fidelity claim is made.

Raw [day](evidence/qa-separated-materials-natural-1/natural-day.png) and
[sunset](evidence/qa-separated-materials-natural-1/natural-sunset.png) captures,
pack fingerprints, failed runs and earlier source snapshots are retained in
the evidence directory. None of these images has been edited for presentation.

Indirect illumination, sky visibility, thin-leaf transmission, water optics,
complete motion and material calibration remain necessary. The next renderer
stage can now consume base tint without baking old occlusion into its albedo.
