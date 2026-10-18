# Material-only shadow lookup: experimental, not promoted

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
