# Material-only shadow lookup: experimental, not promoted

## Follow-up gameplay control: candidate rejected for baseline

`qa-profile-shadow-material-quality-3` completed static and motion phases with
Megabonk closed and approximately 1% device utilization before launch. Source
`6d61f66e8d85fabaed64f93e607951aab28871cb`, runtime JAR SHA256
`C00B75D6AFE6140E5CA3DB3A0E9995F0DF04221E924CD04BEA4EC1D70DB32D10`.
Configuration is unchanged from the six-chunk day setup below. This is image QA,
not a clean performance comparison: it includes readbacks and has no timed B
bracket. Other runtime mod hashes were unchanged.

Across eight static and eight moving sampled frames, **all 128 A/A resource
pairs are byte-identical**. **78 of 128 A/B pairs differ**. Thus reference replay
noise does not explain the observed candidate differences in these frames.
The integer lookup is not accepted into the default renderer. A/A equality does
not establish whether the remaining cause is material classification, other
shader arithmetic/code generation, or a separate error; no driver cause is proven.

The worst-pair retention now gives concrete evidence instead of a lone maximum:

| Path / frame | Maximum distance-component difference | Reference / candidate distance | Pixels with distance difference ≥0.001 | Hit/miss/status switches in retained pair |
| --- | ---: | ---: | ---: | ---: |
| Static / 634 | 109.2414 | 110.2479 / 1.0064 | 13 of 1,638,720 | 1 |
| Motion / 1054 | 121.5593 | 120.5593 / −1 (miss) | 16 of 1,638,720 | 1 |

The static maximum is at input buffer pixel (1650,341); motion maximum at
(372,79), with y in GPU-buffer order. These are rough reflection first-sample
intersection witnesses, not screen-space displacement or a measured visible
artifact size. They demonstrate rare changed intersections despite most numeric
differences being tiny. No conformance tolerance or base quality was reduced.

At teardown all 5272 tracked native-buffer allocations are freed, live attribute
arena/compute descriptor/NGX context counts return to zero, and tracked allocation
failures are zero. This establishes only the lifecycle observed in this run.
Reports, full first/worst buffers, uniforms and mod/pack hashes are archived under
`evidence/qa-profile-shadow-material-quality-3/`; compact summaries are retained
as `profile-shadow-material-quality-3.json` and `*-worst.json` in that archive.

Reproduction (fresh disposable directory, from repository):

```powershell
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-shadow-material-quality-3 -WorkgroupExperiment -ComputeExperiment shadow-material -ProfilePlan 'static,motion' -TimeoutSeconds 2400
```

The historical pilot timing below remains preliminary. There is no reason to
spend another performance run qualifying this candidate before resolving image
equivalence. The independent cloud history experiment remains a separate candidate.

## Configuration and provenance

Minecraft 26.3 / Sodium 0.9.2, Ryzen 7 5800X, RTX 5070, NVIDIA 596.21,
2560×1440 output, native DLSS Quality 1707×960 input, six chunks, world time
3000, full existing GI (eight bounces, four samples), clouds, reflections and
water. Other mod hashes are retained in each runner result and were unchanged.
Each timed phase has 256 warmup and 128 sampled frames. Audio is muted.

- Pilot `qa-profile-shadow-material-1`: commit
  `358602c8a34351fbad756a7e76e6e500604cc439`, JAR SHA256
  `E92D01ED4F0EF0A8C09FD89B99F1F17D38D66206D071A558BDA573821962CB3C`.
- Repeat `qa-profile-shadow-material-2`: commit
  `8b67f9e13614683528eb2429262f57796ae63831`, JAR SHA256
  `A857AA271C06F872EEDF39B02E72D151A7FA778BDAD4535488F199A5B65770E3`.
- Read-only SPIR-V analysis and actual dumped modules are archived with pilot
  evidence. Sources, generated-pack hashes, phase reports, images/buffers and
  GPU telemetry are retained under the ignored `evidence/<run>/` directories.

## Measured pilot: one stationary A/B/A bracket

Before is the mean of the two reference phases. No image readbacks ran here.

| Measurement | Reference | Candidate |
| --- | ---: | ---: |
| FPS | 6.96 | 7.11 |
| GPU world ms | 143.5706 | 140.5449 |
| Active render-thread CPU ms | 2.71 | 2.09 |
| Diffuse GI ms | 43.8825 | 42.5481 |
| Leaf GI ms | 19.0339 | 18.4440 |
| Rough reflections ms | 4.2119 | 4.1000 |
| Native DLSS ms | 1.0414 | 1.0416 |
| Owned native buffers live MiB | 144.67 | 144.67 |
| BLAS / TLAS builds in timed window | 0 / 0 | 0 / 0 |

The apparent frame gain is 2.16%; reference drift is +1.07%. Unchanged cloud.view
also improves by 0.8401 ms, so the whole-frame difference cannot all be assigned
to the material decoder. Sampled device-wide VRAM peaks are 3784/3830/3596 MiB
for A/B/A. Vulkan heap estimates peak at 1988.53/1941.28/1941.28 MiB respectively;
neither measurement is exact process residency. Quality was not checked here.

The optimized SPIR-V still contains a three-vertex loop and barycentric fetch in
the reference preflight material lookup. The candidate reads the same first
vertex's integer material directly. Static non-debug instruction sites in that
region fall 91→57, loads 9→6 and loops 1→0. Ray-query call sites are unchanged.
These are SPIR-V observations, not GPU machine-code instruction/runtime counts.

## Repeat: do not use as isolated performance evidence

Retained prelaunch `*-gpu-info.txt` lists Steam's `Megabonk.exe`. WDDM cannot
quantify its individual utilization, but concurrent GPU work was not excluded.
Raw GPU world means remain: static A/B/A 164.71/159.96/159.72 ms, motion
170.64/171.85/170.42 ms. Geometry/path guards pass within each bracket, but they
do not eliminate external contention. Static reference drift is substantial.
This run also mixes quality readbacks with timing phases, potentially changing
allocator high-water state. Both comparisons are explicitly invalidated by the
evidence-linked `performance-assessment.json`; the analyzer preserves raw rows
and suppresses the FPS-gain claim. Future timings and quality use separate runs.

Quality comparisons: 40/64 static and 26/64 motion resource pairs differ.
Mirror reflection and water outputs are byte-identical for these sampled poses.
Saved first static GI/leaf/direct/rough HDR pairs change 0.277/0.301/0.052/0.390%
of pixels. Most changed rough-hit components differ by at most four ULP, but
rare intersection distances differ substantially. The global 36.08 maximum is
from frame 574, whose full raw pair was not retained; first saved frame is 573.
Do not infer an established Tmin, driver, or material-classification cause.

All 5236 tracked native-buffer allocations were freed; final live buffer,
attribute arena, compute descriptor pool and NGX context counts are zero.
No tracked allocation failure occurred. This is successful teardown of one run,
not proof of general leak freedom or successful image equivalence.

## Next controlled step

The baseline keeps `SULKAN_SHADOW_MATERIAL_LOOKUP=0`. A/A self-comparison now
repeats every experimental pass, rather than only cloud.environment. This checks
whether the reference itself repeats identically before interpreting A/B deltas.
Build 29 succeeds; its runtime JAR SHA256 is
`FFC96A9A343A91A9A5372BBB68250825662B751E762B1F5EC06FD7E1BBAE9982`.
That new self-control has not yet been exercised in game.

Follow-up evidence retention now keeps both the first mismatch and the worst
finite-error pair per phase/pass/resource, with the corresponding uniform buffer
and a JSON sidecar identifying its frame, maximum component byte offset and
reference/candidate values. Later smaller errors do not replace the worst pair.
This addresses the missing frame-574 raw witness above; it cannot recover that
old frame. Nonfinite components remain counted separately, not silently included
in the finite maximum. At most one first pair and one worst pair are retained
per key; intermediate record maxima replace only the worst files.

Build 30 passes. An offline check against the actual `WorkgroupVerification`
class sends errors 0.125, 0.5, 0.25 and an identical pair on frames 10–13. It
verifies first-pair retention, worst frame 11/value/offset, no replacement by a
smaller error or A/A, and seven bounded evidence files. The check source and
outputs are archived under `evidence/worst-witness-check-1/`. This only verifies
the writer; it establishes no real-game numerical equivalence or speedup.

Independent cloud temporal run `qa-profile-temporal-cloud-motion-sunset-1`
failed its geometry guard at warmup index 23, before image capture. The fixed
controller prewarms the trajectory before pinning geometry; retry is still due.
No independent consecutive-frame quality acceptance is claimed.

Reproduce from the repository, using fresh directories and an otherwise idle GPU:

```powershell
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-shadow-material-perf-next -WorkgroupExperiment -ComputeExperiment shadow-material -SkipImageComparison -ProfilePlan 'static,narrow-static,static-repeat,motion,narrow-motion,motion-repeat' -TimeoutSeconds 2400
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-shadow-material-quality-next -WorkgroupExperiment -ComputeExperiment shadow-material -ProfilePlan 'static,motion' -TimeoutSeconds 2400
./tools/Run-TemporalProfile.ps1 -QaDirectory work/qa-profile-temporal-cloud-motion-sunset-next -Sequence motion -ProfileTime 12600 -TimeoutSeconds 3600
python tools/Analyze-PerformanceProfile.py ../../work/qa-profile-shadow-material-1 ../../work/qa-profile-shadow-material-2 --output ../../work/profile-shadow-material-analysis-next
```

No new production optimization is accepted at this checkpoint. Quality and clean
motion performance are unresolved; neither candidate nor profiling stage is done.
