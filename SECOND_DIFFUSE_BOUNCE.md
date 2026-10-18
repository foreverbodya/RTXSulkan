# Additional diffuse vertex (in development)

`-IndirectBounces 2` in the pack generator and disposable world runner enables
one additional diffuse vertex. The default remains 1. This is an experiment
targeting the missing indirect illumination in canopy interiors; numerical,
visual and performance acceptance must be recorded separately.

## Estimator

The first traced hit retains its emitted and directly sunlit radiance. For
nonmetal, nonwater hits it also samples a cosine-weighted diffuse direction,
traces the next terrain surface, and evaluates that terminal surface's emission
and direct sunlight, or the sky on a miss. Secondary air segments use the shared
atmospheric model. Terminal surfaces do not recurse.

Ordinary diffuse throughput is baseColor*(1-F), where F uses the same dielectric
Schlick term as the current receiver model. Leaf surfaces choose reflection or
transmission with probability 1/2 each. Their throughput includes division by
that probability: reflection uses 2*rho*(1-F); transmission uses
2*tau*(1-Fin)*(1-Fout). Existing bounded, uncalibrated rho/tau presets remain.
Front/back receiver fields use separate sample salts as before, and the extra
vertex gets another fixed hash salt. Temporal frame serial affects both.

This extends the current truncated transport estimate. It is not an unbiased
solution of the infinite rendering equation: path depth is fixed, traversal is
bounded to 128 blocks per segment, only diffuse continuation is sampled, and
light/material/atmospheric approximations remain. The general throughput and
path construction basis is described in
[PBRT's path tracing chapter](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/Path_Tracing).
The shader and CPU reference are authored here; no external source was imported.

## Verification plan

Diagnostic mode retains two additional RGBA32F images per receiver field:
terminal position/distance and sampled direction/lobe. The CPU independently
generates directions, intersects source triangles, samples source PNGs and
evaluates terminal light, then compares the complete incoming and receiver
contributions. Source texel boundaries and opposed closest triangles retain
their existing explicit exclusions. Both terminal hit/miss and leaf lobe
coverage are required. Production mode allocates no additional images.

Passing this oracle would establish the bounded model's arithmetic and resource
integration. It would not establish convergence, motion stability, good canopy
lighting, a cinematic result or completed-project 100 FPS. Natural camera
captures, measured cost, unchanged one-bounce regression and clean rebuild are
still required.

## First integrated evidence

`qa-indirect-two-bounces-1` passes on host JAR
`366B0B4F4AF377E2CF6DAD3BFC4B994815C7CAF5AE48E0BF81E8622E7997C3E7`.
Front-field source comparison checks 332 additional vertices (110 terminal hits,
222 sky misses; 78 reflected and 109 transmitted leaf choices). Back-field
comparison checks 770 (424 terminal hits, 346 sky misses; 326 reflected and 412
transmitted leaf choices). Maximum incoming-color error is .00000950 across
these two reports; maximum extra-vertex position error is .000131 blocks.
Source boundary exclusions remain explicit in each report. Emitter removal,
temporal recurrence, spatial passes, water, exposure, camera translation, resize
and reload gates also pass in the integrated run; other mod hashes are unchanged.

Resize emits recoverable `swapchain out of date` warnings before reacquisition;
the run continues through resized DLSS checks and exits zero. This is not a
warning-free run. The runner reports completion only after required markers and
actual process exit; early log fragments are not treated as terminal failures.

`qa-indirect-two-bounces-2` also passes on the subsequent host
`712074B5FD1A420E6ED6B21A16BB4EA26EE3F76DE24576CBE1D5A469471B1054`.
It adds exhaustive finite checks for the extra diagnostic images and the sentinel
check after first-segment sky misses. Front/back additional-vertex coverage is
318/771 samples, including both hit/miss and leaf lobes. Maximum incoming error
in these initial reports is .00000408; complete emitter-removal reports and
integrated lifecycle results are retained in its evidence directory.
`qa-indirect-two-bounces-natural-1` passes production-path execution on the same
host. The two 20-second intervals use eight chunks, 1707x960 input and actual
DLSS Quality to 2560x1440, with no observed throttling:

| Phase | Mean ms | Mean FPS | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| Day | 8.0629 | 124.02 | 10.2673 | 13.0691 |
| Sunset | 10.8237 | 92.39 | 12.9277 | 14.9111 |

These are wall cadence with the CPU pose guard, not GPU pass timings. Geometry
changes during each interval, and differs from the earlier one-bounce run.
Consequently, subtracting their times does not isolate this feature's cost.
Both images were inspected: deep canopy still approaches black and visual
acceptance fails. The second bounce is retained as a verified transport option,
not accepted as a complete fix for canopy lighting.

`tools/probes/Compare-DisplayRegions.py` records display-space statistics and
image hashes without modifying either image. Its fixed ROI includes different
visible geometry in the two runs. The retained day comparison is descriptive;
it does not prove an illumination improvement, equal exposure, or convergence.
A useful next comparison must hold the actual loaded scene constant.

That comparison now exists: [BOUNCE_COMPARISON.md](BOUNCE_COMPARISON.md) records
the same-world 1/2/1 sequence, the initially failed geometry guards and the
renderer change needed to retain identical meshes. It supersedes independent
world generations for estimating the additional vertex's cost. It still rejects
the cinematic image-quality target.

Clean offline rebuild reproduces the tested JAR, sources JAR and native DLL
hashes; see `evidence/second-diffuse-bounce-clean-rebuild.json`. The ray-surface
and display-input CPU contracts also pass on that clean build. This establishes
local binary hash repeatability only. Per-run packs have separate shader and
manifest hashes; no cross-machine or whole-release reproducibility is claimed.
`qa-indirect-two-bounces-baseline-1` passes the original one-bounce mode with
water disabled on the same clean-built host. Source transport, temporal/spatial
checks, exposure, leaf motion and the integrated world lifecycle pass. This
preserves the earlier mode while the extra vertex remains optional.
