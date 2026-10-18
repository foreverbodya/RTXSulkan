# Bounded diffuse depth and canopy comparison (2026-10-16)

Status: an optional depth of 1–8 is implemented. Eight depths pass source/GPU
transport checks; the forest comparison still fails cinematic visual acceptance.
No FPS target applies to this work. DLSS behavior and optimization were not expanded.

## Renderer change

The previous additional diffuse vertex is now an iterative continuation in
`examples/ray-lighting/indirect.comp`. Each visited surface adds shadowed direct
sunlight and material emission; a sky miss terminates with environment radiance.
Ordinary diffuse reflection and the existing 50/50 leaf reflection/transmission
mixture retain their BSDF/PDF weights. The first additional ray keeps its previous
seed; subsequent depths use distinct fixed hash salts. There is no roulette or
radiance clamping. Each ray still has a 128-block maximum.

Atmospheric in-scatter is added for each segment, while only transmittance
multiplies subsequent vertices. This is the iterative equivalent of nested affine
aerial evaluation. Water and metal hits still stop further **diffuse** continuation;
this does not implement specular path tracing or multiple dielectric interfaces.
Leaf optics remain the uncalibrated preset in FOLIAGE_TRANSMISSION.md. One raw
sample, the 32-frame temporal cap and existing spatial filter are unchanged.

`INDIRECT_BOUNCES` is configurable from 1 through 8 in production manifests.
Diagnostic layouts pin their selected depth and retain first-additional-ray
position/direction outputs. The default remains 1; previously installed user files
were not replaced. This extension does not claim that a larger depth fixes the look.

## Independent reference and actual runs

`SecondBounceReference` now recursively computes the bounded continuation from
captured source triangles and original PNG texels in double precision. It checks
the first additional GPU ray metadata and compares the **full continued radiance**
through the existing raw incoming/contribution checks. It does not read later GPU
hit positions as its reference. Existing tolerances and source boundary exclusions
are retained. A deeper-path run must include source-validated paths beyond the
second depth; per-depth counts are recorded, including early sky/material stops.

- `qa-deep-indirect-8-1`: full integrated pass on intermediate JAR
  `F90A77E3C5507FA2B9FB04F3C2108B3CA60EB47272604BF0A374F61C2615F07D`.
- `qa-deep-indirect-8-2`: full integrated pass on final JAR
  `970723C8C7C9BD70E0828FA41F5C2B10512F83F8929A3ECA83EB2D5813860D26`.
  Forward raw samples: 2,484 before emitter removal and 1,425 after. Back-lobe
  samples: 916 and 526. Before removal the backward source reference reaches all
  seven continuation depths with counts 662/298/103/43/20/9/3. Maximum full-path
  incoming error across those four captures is 9.50e-6, contribution error 8.29e-6.
  Existing temporal, spatial, water, camera-motion, exposure, reconstruction,
  resize/reload and retirement gates pass. A resize swapchain-out-of-date warning
  recovered; the subsequent extent gate passed.
- `qa-deep-indirect-two-regression-1`: the full earlier two-depth configuration
  also passes on the final JAR, including source/PNG raw transport and the
  existing integrated gates. Its report and logs are retained separately.

Other mod binaries remained unchanged. These checks establish bounded transport
and integration, not calibrated leaf optics, convergence or finished GI. Variants
3–7 are exposed by the same loop but are not separately claimed as GPU-tested.
Local clean offline rebuilds reproduce the tested JAR, sources JAR
`154A60488D65139D703372794715A2F4721341ABCE04CE027B557BE47BF40BA1`
and unchanged native DLL `F089BA5D…3945257`; see
`evidence/deep-indirect-clean-rebuild.json`. This does not resolve original-source
provenance or NVIDIA redistribution terms.

## Same-world 2/8/2 comparison

`qa-deep-indirect-comparison-1` completes six trials on the final JAR, day then
sunset. All six retain exact source geometry digest
`57012e32f21601189e680d5e658f6589bab805a8537f4b875c6b47e6867c881f`:
932 sections, 1,840,624 triangles, revision 1017. The server is frozen, actual
camera pose is checked every frame, and only the bounce option changes. Each
trial settles and then records a 20-second unthrottled interval at eight chunks.

| Phase | Source HDR whole-frame mean, 8 vs bracketed 2 | Fixed shadow-region mean | Wall ms sequence, 2/8/2 |
| --- | ---: | ---: | --- |
| Day | +0.284% | +0.918% | 8.380 / 21.125 / 8.258 |
| Sunset | +0.074% | -0.369% | 10.993 / 30.106 / 11.092 |

These are single stochastic HDR frames, not converged means or significance
tests. The slightly negative sunset ROI difference is not evidence that adding
nonnegative path contributions reduces expected illumination. Displayed captures
retain adaptive exposure. Timing is observational; it is not an acceptance target.
The daylight images remain visibly too dark under dense canopy. No substantial
visual improvement or Photon-level quality is established.

`Run-QaWorld.ps1 -BounceComparison` now accepts `-ComparisonLowBounces` and
`-ComparisonHighBounces`; the defaults preserve 1/2/1. The initial `-IndirectBounces`
must equal the low value. Capture-marker checks and `Analyze-BounceComparison.py`
validate the actual requested sequence, equal other options and exact geometry.
The generalized analyzer also passes the retained earlier 1/2/1 dataset.

## Reproduction

From the workspace root with the locally locked dependencies and native runtime:

```powershell
& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods `
  -QaDirectory ./work/qa-deep-indirect-new `
  -RayLighting -Reflections -Atmosphere -WorldDlss -AlphaTraversal `
  -IndirectDiagnostic -LeafIndirect -IndirectSpatial -IndirectBounces 8 `
  -AutoExposure -WaterHdr `
  -NativeLibrary ./outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll `
  -RuntimeDirectory ./work/dlss-sdk/lib/Windows_x86_64/rel -TimeoutSeconds 900

& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods `
  -QaDirectory ./work/qa-deep-comparison-new `
  -VisualWorld -BounceComparison -ComparisonLowBounces 2 -ComparisonHighBounces 8 `
  -RayLighting -Reflections -Atmosphere -WorldDlss -LeafIndirect -IndirectSpatial `
  -IndirectBounces 2 -AutoExposure -WaterHdr -RenderDistance 8 -MaxFps 260 -DisableVsync `
  -NativeLibrary ./outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll `
  -RuntimeDirectory ./work/dlss-sdk/lib/Windows_x86_64/rel -TimeoutSeconds 900
```

Directories must be new. This is local reproduction, not independent-checkout
release evidence. Reports, logs, pack manifests/hashes and images are archived
under `evidence/qa-deep-indirect-*`; the comparison summary is
`evidence/deep-indirect-comparison-summary-1.json`.

## Next visual work

The comparison weakens path truncation as the principal explanation for the
canopy appearance in this view. It does not identify a unique cause. Next, assess
sky illumination, the uncalibrated leaf response and display shadow rendition
against a reference with sufficient sampling. The atmosphere currently lacks
multiple scattering; Hillaire's [2020 atmosphere paper](https://onlinelibrary.wiley.com/doi/10.1111/cgf.14050)
provides an applicable approximation to investigate. Caustica's local
`shaders/pipelines/sky_lut/multiscatter.comp.slang` is another implementation
reference, under its LGPL-3.0-or-later project license. No Caustica code was copied
for the diffuse-depth extension. No claim is made that atmospheric multiple
scattering alone will resolve the canopy. Preserve the reported first-person,
sky/border and motion defects as open gameplay work.
