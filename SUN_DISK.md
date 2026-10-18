# Finite solar occlusion

The previous surface light used one centre-direction solar shadow ray. The sky
already draws a disk of authored radius .004675 radians. `sun-source.glsl` now
shares that constant with the sky and supplies an optional eight-direction
tangent-disk quadrature for occlusion. Four equal-area annular midpoints have
antipodal azimuth pairs, giving a centred, deterministic pattern. There is no
new random rotation per frame and no increased source brightness.

`SUN_SHADOW_SAMPLES=1` returns the original centre direction. The eight-direction
option is included only by explicit `-SunShadowSamples 8` or the dedicated
`-SunDiskComparison` generator mode. It is not yet a promoted preset.

The averaged coloured/binary visibility is shared by primary sunlight, sun at
airborne secondary diffuse hits, reflected surface lighting and water's air
reflection/entry illumination. Material BRDF/cosine, irradiance, atmosphere and
cached cloud solar visibility remain evaluated at the disk centre. Refraction
and underwater split solar paths keep their existing centre-ray approximation.
This is finite-source **occlusion**, not a solved continuous area-light BSDF,
per-direction atmosphere or complete underwater area-source model. Eight fixed
samples can still band; camera motion and artistic acceptance remain separate.

No diffuse path count, bounce depth or material survival coefficient changes.
Additional shadow traversal has a real cost. Keep measured image/cadence
comparisons separate from source execution checks.

## Source evidence

`qa-sun-disk-source-1` passes the ordinary synthetic source/lifecycle scenario
with eight disk directions, eight diffuse vertices, four paths per lobe,
preserve-tint foliage, water, atmosphere and native DLSS. Runtime:
0964ECEC7EAA9C0804BD4E1A0E19BE72113A3F0BC1677420D2F391F6B57497E3.
Other mod binaries are unchanged and master sound is 0.0.

The double source reference constructs the disk independently using the exact
golden angle and Moller/source-PNG traversal. On the quartz patch, 2,605 pixels
are checked; three change from centre-ray visibility, and maximum HDR error is
.0004883. This is a narrow penumbra witness, not broad shadow image acceptance.
The original centre-ray coloured-shadow diagnostic remains unchanged and is
checked separately. 2,722 foliage shadow pixels and 1,166 leaf HDR compositions
pass; maximum leaf HDR error is .0002409. Raw indirect paths, water, reload,
resize and retirement are covered by the run's existing checks. This does not
replace the separately failed 64-phase grazing-water sweep.

`sun-disk-reference-properties-1.json` checks 32,768 CPU reference directions,
16,384 antipodal pairs, unit length, cone containment, centred mean and the
tangent-disk second moment. This is not a GPU direction readback or proof of
continuous angular convergence. The actual GPU source comparisons above have
their own scope.

## Natural image and cost comparison

`qa-sun-disk-comparison-1` completed all six day/sunset 1/8/1 captures.
`sun-disk-analysis-1` independently verifies the same frozen source geometry,
actual camera and view matrix, with unrelated options identical. The geometry
digest is f065bbf3f5cb9d47de1dfd9075f6143c0050aaf20ebffd6eb1936ee5275f2d1c.
Actual native screenshots are retained; the coarse whole-frame appearance is
similar, and the subtle penumbra still needs close visual comparison.

The full eight-direction implementation is prohibitively expensive in this
eight-depth, four-path forest: day wall cadence is 118.28 / 426.30 / 117.13 ms,
sunset 269.96 / 702.45 / 277.04 ms. Day diffuse GPU tracing adds 189.75 ms and
leaf diffuse tracing 122.20 ms, while primary solar lighting adds only 2.11 ms.
This identifies repeated solar queries at secondary vertices as the major new
cost; neither this candidate nor the original is a playable preview here.

## Sampled indirect solar visibility: experimental, not promoted

`SUN_INDIRECT_SAMPLES=1`, available only with `SUN_SHADOW_SAMPLES=8`, selects one
of the same eight light directions uniformly from a decorrelated path-seed hash
at each existing diffuse vertex. The target is their average: probability 1/8
and quadrature weight 1/8 cancel, so the estimator is the selected visibility.
Primary lighting and mirror/water reflection retain the full quadrature; path
count, path depths, material terms and source irradiance stay unchanged. This
can increase variance and has not been accepted for temporal image quality.
The default indirect option remains eight, and neither finite-disk mode is
promoted into the user's instance.

The first broad source run, `qa-sun-indirect-source-1`, **failed** at the back
lobe's first ray, pixel 1179964, before its incoming-light comparison. The front
lobe source report passes, but that does not close the failed back lobe.
The failed captures, geometry and frame are retained. Tolerances and hit/miss
gates have not been changed.

`sun-indirect-tmin-witness-2.json`, reproduced by
`tools/probes/Inspect-IndirectNearWitness.py`, isolates the nearest source leaf
triangle at z=25. Its original double ray intersects at .001999534823 m, below
the .002 m minimum; the captured GPU result is .002001141664 m, above it.
Reusing the captured GPU direction with the ideal origin gives .001999534629 m,
so direction rounding alone does not explain the difference. The origin
inferred from the *rounded output endpoint* differs by sub-micrometres in z,
consistent with this threshold crossing. This is not an actual origin-input
readback, an alpha replay, proof of a renderer correction or a revised PASS.
Keep the precision question open while comparing visible/cost consequences.

`qa-sun-indirect-comparison-1` completed its separate six natural 8/1/8 captures
on runtime 00BF651B1A6E1782876F6FAB72F912A873E18BB5A5C86DCCE13D2D10E105F89F.
The same actual camera, inverse view and source geometry are verified *within*
this run; its geometry digest is
6eef58aff48538227f1d38b92f4142627e3708b1794823f699da0009beef9bff.
It differs from the previous point/disk run's digest, so those runs are not a
matched cross-run comparison. `sun-indirect-analysis-1` hashes 594 capture files
and verifies unrelated options remain identical in the six trials.

| Wall cadence, ms | Eight directions | One direction | Eight return |
| --- | ---: | ---: | ---: |
| Day | 448.28 | 120.93 | 461.87 |
| Sunset | 727.49 | 284.08 | 715.27 |

Day ordinary/leaf diffuse GPU tracing changes from 256.65/152.84 to
45.47/28.30 ms. Sunset changes from 312.90/236.89 to 51.12/37.75 ms. Primary
lighting, water and cloud-composite costs remain approximately unchanged.
Path count, diffuse depths and finite-disk primary shadows are preserved.
This reduces the prototype's added cost; it is still not a playable preview.

Actual native day and sunset images were inspected. Separate pre-water/pre-SR
HDR views at each phase's common baseline exposure were rendered by the existing
`RenderLightingAudit` helper and inspected in `sun-indirect-fixed-exposure-1`.
Broad lighting is visually similar; different jitter/seeds prevent pixelwise
noise/convergence claims. Dense-canopy ground remains too dark, foliage flat,
and cloud shapes soft. Static resemblance does not accept motion or temporal
variance. The independent first-ray source failure remains open; this numerical
static execution PASS does not supersede it. No candidate/default/user-instance
promotion is made.
