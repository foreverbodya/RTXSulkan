# Clean environment-only cloud repeat

Run `qa-profile-cloud-env-clean-2`, source
`3569559e894d2d024e115dc23bd14c4fda944541`, clean at launch, runtime JAR SHA256
`6D34DC869A7BEA7C350D32E39036C1280BA4653F72717CE55E07C00B7994B8EA`.
Build 31 passed. The image analyzer finished before launch; no other game was
running. Other mod hashes were unchanged. Complete logs, GPU samples, source/pack
hashes and counters are archived under ignored `evidence/qa-profile-cloud-env-clean-2/`.

## Configuration

RTX 5070 / driver 596.21 / Ryzen 7 5800X / Java 25 / MC 26.3 / Sodium 0.9.2.
Output 2560×1440, native DLSS Quality input 1707×960, six chunks, seed 672026,
frozen forest/river day 3000. GI depth eight/four samples, clouds, water,
reflections and denoising remain enabled. Audio muted, vsync off. Each phase has
256 warmup/128 measured frames; motion follows the same 128-frame sine cycle.
Both A/B/A brackets match source geometry, settings, camera path and revision.

`-SkipImageComparison` now skips all per-phase image readbacks/PNG encoding;
none were created in this run. Initial setup capture is outside the timed plan.
Both variants allocate the exact-noise volume and execute its fill pass, then
select two compiled cloud.environment pipelines without graph/AS reload between
phases. This isolates use of the volume, not its memory overhead versus a graph
without the volume. GPU world excludes UI/swapchain; CPU is active render-thread
time. Nested world/pass intervals must not be summed.

## Before / after

Before is the mean of the two bracketing references.

| Measurement | Static before → after | Motion before → after |
| --- | ---: | ---: |
| FPS | 8.586 → 8.915 | 8.588 → 8.910 |
| Wall cadence ms | 116.472 → 112.165 | 116.437 → 112.232 |
| GPU world ms | 116.404 → 112.070 | 116.346 → 112.181 |
| Active render-thread CPU ms | 2.77 → 3.08 | 2.83 → 2.95 |
| cloud.environment GPU ms | 19.212 → 15.190 | 19.210 → 15.160 |
| Native DLSS GPU ms | 1.0465 → 1.0474 | 1.0500 → 1.0513 |
| FPS gain | +3.84% | +3.75% |
| Reference wall drift | −0.11% | +0.61% |
| BLAS / TLAS builds in timed window | 0/0 → 0/0 | 0/0 → 0/0 |
| Tracked native allocations / uploads in timed window | 0/0 → 0/0 | 0/0 → 0/0 |

No CPU speedup is claimed: small CPU averages rise and Windows thread CPU
measurements are quantized. GPU savings concentrate in cloud.environment, about
4.02/4.05 ms; GI/DLSS are essentially unchanged. All 48 interval rows per phase
are in the [full tables](cloud-clean-pass-times-20261018.md) and
[CSV](cloud-clean-pass-times-20261018.csv). The [JSON](cloud-clean-20261018.json)
includes allocations, transfers, synchronization, configuration and geometry/path
hashes. Other small interval changes remain visible rather than being attributed
entirely to the modified pass.

Owned native live peaks are 144.02 MiB static / 145.13 MiB moving in all variants.
Vulkan estimated heap peaks are 1974.53/1927.28/1927.28 MiB static A/B/A and
1928.47 MiB moving; device-wide sampled VRAM peaks are 3504/3457/3457 MiB static
and 3458 MiB moving. These are distinct scopes, neither exact process residency.
Do not credit the candidate with the static estimate decline. A 130³ RGBA8
volume actually costs 8.38 MiB logical texture storage plus driver overhead;
its ~0.038 ms fill pass exists in both variants here. Earlier canonical-graph
comparisons include the graph difference. Own scene-sync CPU wall averages are
~0.0007–0.0011 ms, not a full GPU stall timeline.

All 5312 tracked native-buffer allocations are freed; final live buffer, attribute
arena, own compute descriptor and NGX context counts are zero; tracked allocation
failures are zero. This is short-run lifecycle evidence, not general leak freedom.

## Quality and decision

Separate independent [static](cloud-temporal-static-20261018.md) and
[motion](cloud-temporal-motion-20261018.md) sunset A/A/B/A runs contain 1024
native frames. Each controls geometry/settings/path/uniforms internally, but
their world snapshots differ from this day timing run: they are not its exact
image witnesses or a cross-run performance pair. Earlier day comparisons remain
separate supporting evidence.

Motion candidate errors are close to controls. Stationary mean error is higher:
0.003168 versus 0.002430/0.002547 RGB8 codes, while maxima are 41 in all three
comparisons. The inspected matched stationary frame shows no obvious change at
the displayed scale. Control variability already exists before DLSS and remains
unresolved. No strict equivalence or universal perceptual acceptance is asserted;
no tolerance, rays, resolution or baseline quality was silently reduced.

**Confirmed:** reproducible ~3.8% full-renderer speedup of this opt-in
representation change. **Not accepted into the default pack:** image differences
and control variability remain open. User Prism instance unchanged. The separate
material-only shadow candidate remains rejected. The stage stays open: ~8.9 FPS
is still far from a usable moving preview.

## Reproduction

From repository, using fresh disposable directories:

```powershell
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-cloud-env-clean-2 -WorkgroupExperiment -ComputeExperiment cloud-3d-env -SkipImageComparison -ProfilePlan 'static,narrow-static,static-repeat,motion,narrow-motion,motion-repeat' -ProfileTime 3000 -TimeoutSeconds 2400
python tools/Analyze-PerformanceProfile.py ../../work/qa-profile-cloud-env-clean-2 --output ../../work/profile-cloud-env-clean-analysis-2
```
