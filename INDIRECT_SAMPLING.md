# Diffuse sampling quality

Status: optional arithmetic averaging of multiple diffuse paths is implemented.
The default is still one path per pixel per lobe. This is a quality/reference
control, not a claim of converged illumination or completed cinematic lighting.

## Transport

`INDIRECT_SAMPLES` accepts 1, 4, 16 and 64. Each path runs the same bounded
integrator, with its own seed and the same scene, receiver and optical parameters.
Sample zero preserves the original seed. Other sample seeds are
`hash(baseSeed ^ (sampleIndex * 0x9e3779b9))`, with unsigned 32-bit arithmetic.
The original front/back salt and temporal frame hash remain part of baseSeed.
Continuation depths use their existing salts on the selected path seed.

The shader sums demodulated diffuse lighting in FP32, divides by the sample
count once, and writes one raw field. A leaf's front and back lobes each take
the configured number of paths. Existing temporal and spatial reconstruction
then consume the mean. Their history count remains a count of frames, capped
at 32; it is not the total number of paths. No extra persistent images, gain,
clamp, ambient fill or leaf-transmission adjustment is introduced.

The generator and QA runner accept `-IndirectSamples`. Diagnostic ray metadata
continues to describe sample zero, while the raw-light image contains the mean.
Diagnostic manifests pin the sample count to their tested configuration.
Production manifests allow option changes, which rebuild the graph/history.

## Source-based checks

The existing source-triangle/original-PNG oracle still validates sample-zero
directions, hits, incoming radiance and additional-ray metadata. It now also
reconstructs every additional sample entirely from source geometry and textures,
including bounded diffuse continuation, to check the final arithmetic mean.
Additional CPU samples do not use GPU hit positions or directions as references.
Atmospheric LUT sampling retains its previously documented, separately checked
input dependency. The contribution tolerance remains `0.003 + 0.004*abs(expected)`.

A pixel is excluded from the mean comparison if any contributing source path
has an existing texture/facing boundary ambiguity. This can substantially reduce
coverage as path count grows. Exclusions and fully checked path counts are
reported explicitly; a passing result does not establish ambiguous-edge behavior.

`qa-indirect-sampling-4-1` passes the full integrated two-depth, four-sample run
on host SHA256 `7519E6639C4D99D7B2185426CCFC5FBDAC3EC3BBCDD825F048861120C1121CB9`.
Atmospheric multiple scattering, leaf front/back transport, temporal/spatial
filter arithmetic, water, exposure, selected AgX display, existing reconstruction,
resize/reload and retirement gates run together. Other mod hashes are unchanged.
Evidence is archived under `evidence/qa-indirect-sampling-4-1`.

| Capture | Fully checked pixels | Source-validated paths | Maximum mean RGB error |
| --- | ---: | ---: | ---: |
| Front, emitter present | 2283 | 9132 | 0.000006102 |
| Back, emitter present | 248 | 992 | 0.000000399 |
| Front, emitter removed | 1289 | 5156 | 0.000001632 |
| Back, emitter removed | 161 | 644 | 0.000000139 |

The known intermittent water-coverage and secondary-distance cases from earlier
runs remain open. This passing run does not close them.

### Grazing-direction numerical audit

The first single-sample regression attempt is retained as a failure:
`qa-indirect-sampling-single-regression-1`, pixel 324982, direction component
error 0.0000186168 against the unchanged 0.00001 limit. This is an additional
case, separate from the older secondary-hit distance discrepancy.

The sampler previously formed `u=(radialBits+0.5)/2^24` in FP32 and then computed
the normal component with `sqrt(1-u)`. Near `u=1`, rounding u before subtraction
loses half-bin precision. The replacement computes the equivalent complement
`(0xffffff-radialBits+0.5)/2^24` before conversion, avoiding cancellation.
Seed/hash mapping, the ideal distribution and existing CPU double formula are
unchanged. Both initial rays and continuation rays share the corrected helper.

An opt-in GPU diagnostic evaluates 2048 bins at each endpoint under four normal
bases, comparing 16,384 directions/PDFs with ideal double midpoint sampling.
It also evaluates the old subtraction on the GPU and requires the test to
detect out-of-tolerance counterexamples. Production packs allocate none of these
diagnostic images or passes. The second regression attempt is retained as a
diagnostic setup failure: its unconsumed pass was pruned from graph dependencies.
The generator now explicitly roots the diagnostic images through a debug-only
dependency of the direct-light pass. This is not a production rendering change.

The actual endpoint check in `qa-indirect-sampling-single-regression-3` passes:
maximum direction error 0.000000185111 and PDF error 0.000000060558.
The old subtraction exceeds the existing direction tolerance in 138 cases,
with maximum error 0.0000715071. This establishes the isolated numerical fix;
it does not claim exact replay of the historical pixel's missing frame inputs,
nor does it close the separate intermittent distance discrepancy.

The complete single-sample regression, including legacy display, subsequently
passes in `qa-indirect-sampling-single-regression-3` on host
`6037801D5FDB271676EEB6F35580AAA9DEE4A9DB8FD4FC5E9DBAF13009C80994`.
The earlier natural comparison and initial four-path fixture used the previous
host and sampler; their archived identities are retained, not relabeled.

`qa-indirect-sampling-4-2` also passes the full four-path/AgX configuration on
that final host with the corrected sampler and explicit endpoint check. Its
front/back raw means retain the existing transport tolerances. A swapchain
out-of-date warning occurred during resize; the subsequent extent/lifecycle
gates passed. This warning is preserved in the archived log.

## Natural comparison

`Run-QaWorld.ps1 -VisualWorld -SamplingComparison -IndirectSamples 1` performs
1/16/1 trials at day and sunset in the same loaded, frozen world. The exact
geometry digest, actual camera and all other options are checked across trials.
The existing comparison analyzer accepts this sequence as well as the earlier
bounce and atmosphere sequences. Captures retain adaptive exposure and existing
reconstruction. Single captured frames are not converged reference means or a
variance estimate. Visual conclusions must be based on the completed images.

`qa-indirect-sampling-comparison-1` completed all six trials on the same host.
All retain source geometry digest
`301b918d5f74cf932044be43661de38cf3ecc472fb2d9b90cfa2cceb3bab4328`:
931 sections, 1,841,122 triangles, revision 994. Both day and sunset pairs were
visually inspected. The dark canopy remains rejected; the 16-path images do not
show a material aesthetic improvement. This weakens insufficient raw sampling
as the primary explanation in this view, without proving a unique cause.

| Phase | Fixed shadow ROI mean, 16 vs bracketed 1 | Baseline bracket drift | Exposure sequence, EV |
| --- | ---: | ---: | --- |
| Day | +0.367% | +0.083% | 0.918225 / 0.917966 / 0.918239 |
| Sunset | -1.427% | -1.776% | 4.999984 / 4.999998 / 4.999985 |

These are single source-HDR frames, not averaged illumination or a significance
test. The negative sunset difference does not demonstrate lower expected energy
from more samples. The unchanged sunset exposure ceiling also remains visible.
The selected display curve is AgX in every trial. The default sample count stays
1; neither 16 nor 64 is promoted as a visual fix. Full results and pack hashes
are archived, with summary `evidence/indirect-sampling-comparison-summary-1.json`.

Four-sample source checks do not by themselves establish source validation of
16 or 64 samples. Further DLSS development and optimization remain deferred;
there is no FPS acceptance goal.

## Next visual investigation

Audit actual material reflectance and the light components reaching shadowed
receivers. In particular, the current leaf preset treats mesh faces as thin
scattering surfaces for diffuse paths, while the solar-shadow approximation
counts inward crossings. Their relationship to Minecraft leaf blocks, texture
holes and any culled interior faces must be made explicit before adjusting
transmission. This is a modeling question, not an established numerical bug.
Do not introduce a blanket brightness multiplier based on these images alone.
