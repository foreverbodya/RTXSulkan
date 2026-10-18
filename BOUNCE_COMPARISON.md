# Same-world diffuse-bounce comparison (in development)

## Preserve-tint canopy follow-up

qa-canopy-bounces-1 executes six frozen **2/8/2** captures with FOLIAGE_OPTICS=2,
four raw paths per lobe, existing filters, atmosphere, water and native SR.
Geometry/inverse-view matrices match; jitter/ray seeds differ. See
canopy-bounces-analysis-1.

| Group without primary sun: unexposed local luminance | Two | Eight | Return two |
| --- | ---: | ---: | ---: |
| Day leaves | 0.0085898 | 0.0108558 | 0.0085568 |
| Day ordinary diffuse | 0.0048775 | 0.0052692 | 0.0048109 |
| Sunset leaves | 0.0016359 | 0.0018108 | 0.0016397 |
| Sunset ordinary diffuse | 0.0009612 | 0.0009856 | 0.0009638 |

Actual images show modest improvement; dense canopy remains dark. More depth
alone does not establish the intended look. This is natural execution/component
evidence, not converged or independently source-validated eight-depth transport
with this optical variant. Its separate source fixture is still required.

The shared lighting analyzer makes no throughput claim and accepts the
intentional QA cap. The earlier unthrottled cadence analyzer remains unchanged;
the user withdrew the FPS acceptance target. Existing defaults are not promoted
from these observations.

## Earlier comparisons

Earlier independent natural-world generations have different captured geometry.
Their FPS and pictures cannot isolate the effect of the additional diffuse
vertex. `Run-QaWorld.ps1 -BounceComparison` now uses one marked disposable world
and the sequence 1/2/1 at day, then 1/2/1 at sunset. It requires VisualWorld,
LeafIndirect, IndirectSpatial and an initial IndirectBounces value of 1.

## Measurement guard

The server is frozen after setup; camera and shader-camera validation retain
their existing guards. Each trial settles for at least 20 seconds and requires
five seconds without captured-geometry changes before its 20-second interval.
No GPU readback or source hashing occurs inside that interval. Unthrottled wall
cadence, source HDR statistics, exposure, DLSS execution and displayed PNGs are
recorded separately. Adaptive exposure and stochastic sampling remain active;
this is not an equal-exposure or converged image comparison.

The geometry digest includes section coordinates, opacity, vertex stride/count,
every packed source vertex word and shared indices. Mesh list order is sorted
by digest; vertex order within each mesh is retained. Digest equality is required
between trials within each day/sunset phase. Geometry revision must also remain
unchanged throughout each timing interval. Per-section and per-word hashes aid
diagnosis; no source fields are silently excluded to make a comparison pass.

The active renderer's actual INDIRECT_BOUNCES option must match the trial label.
Production manifests now allow 1 through 8; diagnostic manifests keep their fixed chosen
value because their extra storage-image layout depends on that choice. Switching
uses the same NativePackRuntime.applyOptions path as the settings screen.

`tools/probes/Analyze-BounceComparison.py` requires a successful terminal run,
six correctly ordered captures, equal source digests, steady intervals,
unthrottled rendering and a frozen server. It compares two-bounce cadence with
the mean of the bracketing one-bounce intervals, reports baseline drift and
single-frame source-HDR statistics. These remain observations, not statistical
significance, GPU pass timings, gameplay or cinematic acceptance.

## Retained failures and renderer change

`qa-bounce-comparison-1` stops after the first trial because source digests differ
after changing the bounce option. `qa-bounce-comparison-2` adds per-section
diagnostics and fails the same strict guard: 925 sections before, 801 after,
124 removed, none added, two shared sections also differing. These runs are
not accepted comparisons. The evidence establishes a changed captured working
set; it does not establish that the world blocks themselves changed.

Previously NativePackRuntime.activate rebuilt all terrain whenever either
renderer used material attributes, even for compute-only option changes.
It now preserves accepted meshes if MaterialTable.sameVertexEncoding confirms
the same enabled vertex format, numerically equal BlockState-to-ID mapping and
vegetation-wind encoding. Enabled/disabled format changes, changed mappings or
wind changes still trigger rebuilding. IdentityHashMap's value-identity equality
is deliberately avoided for IDs outside Integer's small-value cache.

MaterialEncodingContract passes after compiling the probe with javac into a
normal classpath directory. It covers independent equal mappings, ID changes,
removed selectors, wind changes, absent/disabled formats and explicit ID zero.
Two earlier probe-launch failures are retained: Java source mode first required
the package path, then used a separate class loader that prevented package-level
access. Neither was a renderer failure.

## Accepted measurement run, rejected visual result

`qa-bounce-comparison-3` completes all six trials on JAR SHA256
`828C5C509E1F7E4F7EA92246AAD762344E07359A5B4B5D5BB796BFA487A87629`.
All three trials within each phase have source geometry digest
`90b2cbf5b93f41e7fcb0e0359a273de51c4d64e1fcb34d688a2ef67d6dbc3f81`.
The captured geometry revision remains 1011: 954 sections and 1,822,758 triangles.
The summary tool also confirms equal logical RT sections/meshes/attribute-byte
counts, no pending RT geometry, matching other renderer options, validated
camera, unthrottled intervals and 1707x960 to 2560x1440 reconstruction.

| Phase | One bounce A | Two bounces | One bounce B | Extra ms vs bracket mean |
|---|---:|---:|---:|---:|
| Day mean ms | 5.6848 | 7.5369 | 5.6783 | 1.8554 |
| Day mean FPS | 175.91 | 132.68 | 176.11 | — |
| Sunset mean ms | 7.0805 | 9.9468 | 7.0730 | 2.8700 |
| Sunset mean FPS | 141.23 | 100.53 | 141.38 | — |

Bracketing one-bounce timing drift is -.114% day and -.106% sunset. These
20-second intervals include the CPU camera guard, use eight chunks and a frozen
world. They do not prove performance in gameplay or with the unfinished features.

The central source-HDR ROI's mean luminance is 2.73% higher with two bounces in
the day snapshots and 1.91% higher at sunset, relative to the bracket mean.
These are single stochastic frames, not converged estimates. Display exposure
remains adaptive and is recorded separately. Day/two-bounce and both sunset
variants were visually inspected; deep canopy still approaches black. The
overall cinematic image remains rejected. Do not treat more bounces alone as
a sufficient fix for that problem.

Full source reports, images and the analysis are retained under
`evidence/qa-bounce-comparison-3` and `evidence/bounce-comparison-summary-3.json`.
Local clean JAR/sources/native rebuild hashes repeat; see
`evidence/bounce-comparison-clean-rebuild.json`. However,
`qa-bounce-comparison-regression-1` fails at stage 7 with
`Water motion has no primary source hit`. Its cause is not yet established.
This host is not promoted as the fully passed gameplay preview. The earlier
712074B5…71B1054 host and its exact production pack remain the installed preview
after the user's explicit installation request. The successful comparison does
not override the failed regression.
