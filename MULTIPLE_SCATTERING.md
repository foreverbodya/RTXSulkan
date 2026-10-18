# Atmospheric multiple scattering (2026-10-16)

Status: implemented as an optional shared atmospheric source. Numerical atmosphere
checks and a matched natural comparison pass. Two full regression attempts are
rejected and retained below; this is not a release or general gameplay acceptance.
The user's quality-first priority applies; DLSS and optimization are deferred.

## Model and reuse

The existing RGB single-scattering atmosphere now optionally adds an isotropic
higher-order closure following Hillaire (2020). The author's
[reference implementation](https://github.com/sebh/UnrealEngineSkyAtmosphere/tree/183ead5bdacc701b3b626347a680a2f3cd3d4fbd)
is MIT licensed, copyright Epic Games (2020). Its quadrature/closure is adapted
to Sulkan's existing medium, graph and coordinate system. The complete MIT notice
is retained in the host JAR's `META-INF/licenses/` and generated pack's `LICENSES/`.
No Caustica code was imported. The upstream commit and file hashes are recorded
in `evidence/atmosphere-multiple-upstream-reference.json`.

A 32x32 RGBA32F table uses sun-zenith cosine and quadratic altitude in [0.001,60]
km. Each texel integrates 64 fixed sphere directions, 32 nonuniform view segments
and 12 solar-transmittance segments. The isotropic unit-irradiance source and
medium-scattering feedback are averaged over direction; the source is closed as
`meanRadiance / (1 - meanFeedback)`, with a 1e-4 denominator safety floor.
Lambertian planet-ground direct reflection uses the existing albedo 0.1.
This retains the method's isotropic approximation, not a fully resolved volume
path tracer or calibrated spectral model.

`-MultipleScattering` requires `-Atmosphere`. The generated pack exposes
`ATM_MULTIPLE_SCATTERING` values 0/1, default 1. Without the switch the earlier
single-scattering graph and ten-texel lighting layout remain. With it, an eleventh
lighting texel contains the observer's isotropic source, scaled by solar irradiance
once. Visible sky integration and the shared aerial helper use the new source.
Direct light, foliage, reflected/refracted HDR and indirect legs already consume
those shared inputs; their CPU composition references now include the extra term.

The table is rebuilt each frame, even when its contribution is toggled off in a
comparison. Its small history allocation prevents end-frame diagnostic readback
from seeing aliased storage. It is not accumulated from previous table values.
Caching and scheduling optimization are deferred. The existing clear-Overworld,
above-air model and dawn/weather fallback limitations remain; this does not
complete night, clouds, weather or other dimensions.

## Numerical evidence and limits

`qa-atmosphere-multiple-1` fully passes on intermediate JAR
`501B4764929DFCD499EE1B20A1842182EA935AF3338BB8D49DBD5B6D3D17E2AC`.
It includes atmosphere, diffuse/leaf transport, temporal/spatial reconstruction,
water, camera motion, exposure, native SR, resize/reload and retirement checks.

The multiple-scattering oracle checks finiteness/nonnegativity of all 1,024 GPU
table cells and compares **20 selected cells** with a double CPU reference using
uniform 128-step view integration and Simpson solar quadrature. Both use the
defined 64-direction rule; this does not verify angular convergence. Maximum
absolute closure error is 7.35e-5; maximum reference medium feedback is 0.289.

The 32 higher-resolution CPU sky-view comparisons use that separately checked GPU
table as an input. They do not independently rebuild every lookup cell. Solar
attenuation, SH arithmetic, observer-source interpolation, visible sky and the
solar disk are checked separately. Existing tolerances were retained. Source/GPU
raw diffuse and leaf checks include the added per-segment aerial contribution.
These are scoped numerical checks, not cinematic acceptance or full physical
calibration.

## Same-world visual comparison

`qa-atmosphere-multiple-comparison-1` passes on JAR
`5E32803B3E4779B07F2FC3265B8BD58723B65541AAE4DDFF996D1F2ECBA841CC`.
New `-AtmosphereComparison` uses the existing guarded natural-world controller
with the sequence **0/1/0**, at day then sunset. The server is frozen, camera is
validated each frame, and all other options remain equal. All six source geometry
digests are `a9bde43a3d59fc31bf0d3bbd41eb098db20297f091f1be1a0a3715d47a2763b8`:
931 sections, 1,821,534 triangles, revision 975.

| Phase | Whole HDR mean vs off bracket | Fixed shadow-region mean | Display EV, off/on/off |
| --- | ---: | ---: | --- |
| Day | +13.89% | +12.86% | 1.134 / 0.959 / 1.134 |
| Sunset | +19.32% | +24.94% | 5.000 / 5.000 / 5.000 |

These are single stochastic HDR frames before SR/exposure, not converged or
statistically established estimates. Off-bracket ROI drift is +0.054% day and
-2.115% sunset. The daylight sky's blue and overall illumination visibly change;
adaptive exposure offsets part of the brightness increase. Sunset water/foliage
also change, but deep canopy remains too dark and the overall image is rejected
against the cinematic target. The sunset display is at its current +5 EV cap.

Recorded wall ms sequences are 9.143/9.161/8.670 day and 11.376/11.885/11.028 sunset.
Because **all three trials execute the table bake**, those numbers do not isolate
its performance cost. No FPS target is imposed.

The comparison analyzer now accepts the selected option and its actual values,
while preserving earlier bounce-comparison support. It checks equal geometry,
other options, logical RT scene counts, camera and 1440p Quality extents. Original
bounce datasets remain unchanged. The structured result is
`evidence/atmosphere-multiple-comparison-summary-1.json`.

## Retained regression failures

Both following attempts use `5E32803B…BA841CC`; neither is labelled passed:

- `qa-atmosphere-multiple-2` passes the day atmosphere reference but fails stage 7:
  `Water motion has no primary source hit`, pixel (1359,183), 1707x960. The saved
  source/frame case independently replays with no strict center-ray hit and a
  closest barycentric coordinate -8.73e-6. The center is 0.00062076 pixels from a
  projected triangle edge. Actual RTX 5070 raster precision is 8 fractional bits
  (quantum 0.00390625 pixels). Nearest rounding of projected vertices yields
  coverage in an offline diagnostic. That is consistent with raster edge coverage;
  it is not proof of the GPU's exact rounding/clip/tie behavior. No source-hit,
  motion or coverage acceptance was changed. The case, replay and original failure
  remain in the run archive; hardware limits are in `water-raster-limits.txt`.
- `qa-atmosphere-multiple-disabled-1` omits the feature and fails stage 2 at a
  second-ray distance comparison: pixel 1376285, CPU 10.4540054721 versus GPU
  10.4592161179, existing 0.002-block tolerance. The cause is unestablished. This
  is not an accepted default-path regression and is not blamed on the new sky term.

Failure-only recording now preserves source geometry, first source-hit inputs,
seed and additional GPU hit/direction in `second-bounce-reference-failure.json`
if the second case recurs. It changes no acceptance predicate. These open failures
prevent calling the current host fully validated, even if a later retry succeeds.
The user's installed preview and other mod binaries have not been replaced.

`qa-atmosphere-multiple-3` fully passes on the subsequent diagnostic-recording
host `B2B6F09E7C18428F7E6861273BAEEB4C706928ABF49EF67573D8BC93F37FFCE9`.
Neither intermittent failure recurs in this attempt. This does not close their
investigation or establish that the failure recorder was exercised.
`qa-atmosphere-multiple-disabled-2` also passes the full two-depth, no-multiple-
scattering regression on that same host. Both failed attempts remain part of
the evidence; a successful later sample is not proof that an intermittent
discrepancy cannot recur.
Local clean offline builds reproduce that JAR, sources JAR
`3546C36BC25B2AE118C015D346F3FCC1E5257B7F09806DE5AAB8B9E6EE3B9C27`
and unchanged native DLL `F089BA5D…3945257`; see
`evidence/atmosphere-multiple-clean-rebuild.json`. No original-source or
independent-checkout release claim follows from local hash repeatability.

## Reproduction

From the workspace root with the local locked dependencies; directories must be new:

```powershell
& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods -QaDirectory ./work/qa-multiple-new `
  -RayLighting -Reflections -Atmosphere -MultipleScattering -WorldDlss `
  -AlphaTraversal -IndirectDiagnostic -LeafIndirect -IndirectSpatial `
  -IndirectBounces 2 -AutoExposure -WaterHdr `
  -NativeLibrary ./outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll `
  -RuntimeDirectory ./work/dlss-sdk/lib/Windows_x86_64/rel -TimeoutSeconds 900

& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods -QaDirectory ./work/qa-multiple-compare-new `
  -VisualWorld -AtmosphereComparison -RayLighting -Reflections -Atmosphere `
  -MultipleScattering -WorldDlss -LeafIndirect -IndirectSpatial -IndirectBounces 2 `
  -AutoExposure -WaterHdr -RenderDistance 8 -MaxFps 260 -DisableVsync `
  -NativeLibrary ./outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll `
  -RuntimeDirectory ./work/dlss-sdk/lib/Windows_x86_64/rel -TimeoutSeconds 900
```

This is local evidence, not independent-checkout reproduction. Original Sulkan
source provenance and NVIDIA redistribution terms remain unresolved. Next visual
work must distinguish insufficient illumination/leaf response from display shadow
compression, using adequate sampling and matched images; preserve the two rejected
regressions for investigation. Increasing sky energy alone has not completed the look.
