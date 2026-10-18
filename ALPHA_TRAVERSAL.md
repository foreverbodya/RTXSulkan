# Alpha-tested terrain rays

Date: 2026-10-15. Engineering implementation and scoped verification; not a cinematic release.

The later camera-relative scene and updated tested artifact are recorded in
`RAY_COORDINATES.md`. The alpha milestone's original hashes/evidence below remain historical.

## Implementation

`sulkan/ray-terrain.glsl` exposes a shared `sulkanTraceTerrain` helper. It preserves
hardware commits for opaque geometry and visits nonopaque cutout triangle
candidates. A candidate is confirmed only when base-level atlas alpha multiplied
by interpolated vertex alpha meets the accepted Sodium mesh's cutoff. The flags
come from `a_LightAndData.z` (bits 1..2 select 0, 0.1, 0.5 or 1), separately from
Sulkan material-class IDs. This was checked against the installed Sodium 0.9.2
shader ABI. Mask 1 is opaque, mask 2 cutout; mask 4 translucent is excluded.

The direct-light shadow, smooth-metal reflection and reflected-hit shadow paths
now call this helper. Closest-hit queries traverse to completion; shadow queries
request terminate-on-first-accepted-hit. This extends the existing mesh attributes
and acceleration structures; BLAS/TLAS construction and the NGX bridge were not
rewritten. The direct-light pass now requests the attribute arena and live atlas.

Ray alpha currently uses explicit LOD 0. It does not establish footprint/mip parity
with rasterization at distance. Wind-deformed raster geometry is not yet reproduced
in BLAS, and transparent glass/water transmission remains separate unfinished work.
The alpha diagnostic disables wind and uses fixed-LOD raster sampling explicitly;
normal pack generation retains its configured wind and raster sampling policy.

Pack use requires `rayScene: true`, `rayAttributes: true`, the live
`$terrainAtlas` sampler and `#include "sulkan/ray-terrain.glsl"`. The helper returns
a rejected-candidate count for diagnostics and leaves the supplied query ready
for committed-hit attribute reads.

## Evidence

Tested JAR SHA256:

`A75B2B994878CD7C047343D18F00A0C631DA3DAB113D0AF1CA6EF5F00583EE96`

A subsequent clean offline build reproduced this JAR byte for byte. Rebuilding
the external native adapter reproduced SHA256
`F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257`.
`evidence/alpha-build-repeat.json` links all three passing runs to these artifacts,
records unchanged other-mod binaries and confirms 13 core AS/NGX source files
are unchanged from the pre-research snapshot. Each passing evidence directory
also records the generated pack manifest and every pack file's SHA256; shader
sources outside the JAR are therefore identified separately.
This is current local-toolchain repeatability, not an independent machine build.

`evidence/alpha-reflections-dlss-6/result.json`: PASS, exit 0, all required markers,
other mod hashes unchanged. Real Minecraft 26.3/Sodium 0.9.2 on the RTX 5070.

| Alpha check | Result |
|---|---|
| Pixels whose forced-opaque first hit is an oak leaf | 255,012 |
| Reference-only 3×3 raster surface/silhouette boundary exclusions | 83,980 |
| CPU-selected alpha texel boundary exclusions | 9,092 |
| Verified accepted first-layer alpha pixels | 123,708 |
| Verified first-layer holes | 38,232 |
| Rays reporting rejected candidates | 38,512 |
| Maximum closest-hit distance error versus CPU | 0.0000462 blocks |
| Verified CPU backface hits | 27,377 |
| Raster depth differences attributable to verified backfaces | 15,605 |

The CPU oracle independently intersects captured mesh triangles in double precision,
unpacks their UV/vertex alpha, and samples the resource manager's oak PNG through
the current stitched sprite mapping. It does not use Vulkan traversal or shader
barycentrics. It uses the same accepted Sodium mesh source; earlier capture tests
cover that source ABI separately. The oracle asserts a stationary, binary-alpha
oak sprite and fails on intersected cutouts outside that sprite.

Every selected nonboundary ray is checked against the full CPU closest hit, including
geometry behind holes and backfaces. Forced-opaque depth is also compared against
the CPU's unfiltered first triangle. First-layer opaque/hole labels come from CPU
texture alpha, not a distance threshold. Raster depth remains an additional check;
a discrepancy is allowed only when the first or accepted CPU surface is a backface,
with the GPU-facing flag also checked for an accepted backface. Sodium's camera
face selection and two-sided terrain ray queries otherwise describe different
visible surfaces behind a hole.

The same combined run also passed:

- 11,808 offscreen-emitter reflection samples, HDR composition and removal (4,573
  selected miss samples after removal). The complete emitter is outside the view.
- World NGX Quality 1707×960→2560×1440, then 1280×720→1920×1080, finite/nonuniform
  output checks, stationary jitter and camera motion in two directions.
- Generation resize, world/pack lifecycle and final resource retirement.

Two separate real-game regressions passed on the same JAR without alpha diagnostic
passes, fixed-LOD raster override, wind override or the nearby leaf wall:

- `evidence/alpha-reflection-regression-1`: ordinary reflection pack, offscreen
  emitter intersection/removal, world NGX Quality, camera/jitter, resize/retirement.
- `evidence/alpha-direct-regression-1`: ordinary direct-light pack; 2,605 independent
  quartz BRDF/shadow samples (90 shadowed, 2,515 sunlit), maximum absolute error
  0.000489, plus world NGX Quality, camera/jitter, resize and retirement.

Both result files report exit 0, no missing markers and unchanged hashes of Fabric
API, Sodium and Voicechat. These regressions establish the retained engineering
coverage; they do not establish correct moving foliage or reflected-motion guides.

The closest-hit alpha oracle is not a separate quantitative test of the shadow
query's terminate-on-first-hit mode or a moving-foliage test. Actual consumers
share the helper, but these verification scopes remain distinct. The diagnostic
adds full-screen ray passes and CPU/readback work; its frame cadence is not a
production benchmark.

## Retained failed runs and why the checks changed

- `alpha-compile-failure-1`: GLSL rejected `inout rayQueryEXT`; opaque query handles
  use an unqualified parameter. The [Khronos extension specification](https://github.com/KhronosGroup/GLSL/blob/master/extensions/ext/GLSL_EXT_ray_query.txt)
  defines pointer passing for query objects. The corrected helper compiled in subsequent games.
- `alpha-raster-oracle-failure-2`: a full raster-depth comparison failed behind a
  leaf hole.
- `alpha-backface-evidence-3`: added GPU-facing/normal evidence confirmed the hit
  was the underside of a leaf (`normal=(0,-1,0)`, backface), omitted by the camera
  raster path. This led to adding the full independent CPU triangle/alpha oracle.
- `alpha-coverage-failure-4`: CPU comparisons passed the small scene's selected
  samples, but only 8 holes survived the boundary rules. The minimum remained 20;
  a larger nearby leaf wall was added instead of lowering the threshold.
- `alpha-front-layer-oracle-failure-5`: labelling a hole by a 0.02-block separation
  was invalid near adjacent/backfacing geometry. Labels now use independent source
  alpha; full closest-hit distance still has the original 0.015-block tolerance.

These runs remain FAIL. Exit code 0 from an orderly diagnostic shutdown is not
treated as success when a required marker is missing or a QA failure is logged.

## Reproduction

```powershell
./tools/Run-QaWorld.ps1 -InstanceMods 'C:/path/to/verified/mods' -QaDirectory 'C:/path/to/new/alpha-qa' -RayLighting -AlphaTraversal -Reflections -WorldDlss -NativeLibrary 'C:/path/to/sulkan_ngx_probe.dll' -RuntimeDirectory 'C:/path/to/DLSS/lib/Windows_x86_64/rel' -MaxFps 260 -DisableVsync
```

Use a fresh disposable directory and the existing dependency locks. Native SDK
and runtime paths remain external. Generate a normal pack without `-AlphaDiagnostic`
to omit the diagnostic passes and preserve ordinary wind/sampling settings.

## Remaining work

Alpha-aware shadow-specific oracle, alpha verification across atlas reload,
nonbinary/mip-filtered cutouts, matching animated geometry and motion history,
camera-relative TLAS, general rough reflections/denoising, physical atmosphere,
water/transmission and traced indirect light. Representative 1440p/~100 FPS,
Photon-level appearance, independent clean-checkout builds and release licensing
remain open acceptance requirements.
