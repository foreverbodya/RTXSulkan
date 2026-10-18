# Diffuse indirect lighting

An optional single-sample diffuse bounce is now connected to visible HDR before
DLSS SR. Optional temporal accumulation and spatial filtering are implemented;
image-quality acceptance remains open. Their validation is recorded below.
This is not a finished GI mode.
The working leaf checkpoint is recorded in FOLIAGE_TRANSMISSION.md. Its hashes
and original fixtures remain the regression baseline.

## Optional spatial reconstruction (2026-10-15)

`-IndirectSpatial` implies temporal accumulation and adds three geometry-guided
a-trous passes at steps 1, 2 and 4 before direct HDR composition and NGX SR.
The separable binomial stencil is [1,4,6,4,1] in each dimension. Inputs are
demodulated lighting and current temporal position/material/normal guides.
Neighbors require a valid receiver, exact material, normal dot above .98,
plane separation at most .02 blocks and a footprint-scaled distance capped at
2 blocks. Accepted weights also include normal-dot^32 and exp(-plane/.01).
The filter preserves the center's temporal count and never feeds spatially
filtered lighting back into temporal history. There is no radiance clamp.

This is a biased spatial estimate. It has no moments or variance adaptation;
coplanar lighting discontinuities and narrow indirect shadows can soften.
Unsupported leaf/translucent receivers are not repaired by this filter. It does
not add missing bounce energy, exposure control or water optics. Production
resources are non-history RGBA16F targets (12.50 MiB each logically at 1707x960);
diagnostic generation pins their history pairs for end-frame CPU readbacks.
Do not use diagnostic allocation or cadence as production memory/cost evidence.

Host `73F4A63FE57199A5ADE03552B367BC56FA1BCEA9F6D550DD3C1EE9918485BEFD`
passes `qa-indirect-spatial-1`. Independent double arithmetic checks each pass
against actual source lighting and guides, with .001 + .0015*abs(expected)
FP16 tolerance. Guides/raw transport and temporal recurrence have separate
oracles; using actual filter inputs is not an independent transport reference.

| Step | Valid centers | Centers using neighbors | Invalid centers preserved | Maximum RGB error |
|---|---:|---:|---:|---:|
| 1 | 631 | 631 | 8785 | .000347463 |
| 2 | 631 | 630 | 8785 | .000244054 |
| 4 | 631 | 629 | 8785 | .000233962 |

All passes exercise material, normal and distance rejection; step 4 also records
10 plane-rejected taps. Final HDR composition uses the filtered input and passes
the existing independent primary lighting reference. Temporal stable/motion/
scene-change, alpha/leaf, 32-phase atmosphere, reflection/removal, actual NGX SR,
reload/resize and scene retirement also pass. Other mod hashes remain unchanged.
The first run's `result.json.scope` text incorrectly inherits the older temporal
description saying no spatial denoising. Its explicit `indirectSpatial=true`,
generated pack and three-pass report record the actual coverage. The original
report is retained unchanged; the runner's text is corrected for future runs.
Natural non-history allocation, high-sample convergence, matched cost and visual
acceptance remain separate gates; numerical filter agreement alone proves none
of those. The temporal-only checkpoint below remains archived unchanged.

`qa-indirect-spatial-natural-1` passes the production non-history pack at day and
sunset on the same host, including finite HDR/NGX, actual camera validation and
unchanged other mods. Actual imagery still fails cinematic acceptance: canopy
interiors and sunset are excessively dark, water still uses the old fallback.
Leaf receivers do not participate in the new diffuse estimator. No quantitative
noise reduction is claimed: independently generated geometry differs from the
previous natural runs, and no high-sample matched image reference exists yet.

| Eight chunks, 20-second window | Day | Sunset |
|---|---:|---:|
| Frame intervals | 5356 | 4562 |
| Mean wall ms | 3.73369 | 4.38412 |
| p95 / p99 wall ms | 4.5485 / 7.1689 | 5.1503 / 7.6212 |
| Supported pixels in post-window history capture | 490506 | 491010 |
| Mean history count | 22.60380 | 22.57324 |
| Fraction at cap | .525724 | .527912 |

Both intervals have changing geometry and no throttle; CPU pose guards are part
of the wall measurement, while all GPU readbacks begin afterwards. These are
unfinished-renderer wall intervals, not isolated filter cost, a matched A/B
measurement, GPU timing or confirmation of the finished 100 FPS product target.

The same host passes `qa-indirect-spatial-disabled-1` (temporal-only path) and
`qa-indirect-spatial-feature-off-1` (previous direct/material/atlas path).
Other mod hashes remain unchanged. A clean local offline rebuild reproduces
the JAR and native hashes; CPU surface/payload checks pass. See
`evidence/indirect-spatial-clean-rebuild.json`. This does not settle original
source provenance, NVIDIA distribution or cross-machine reproducibility.

### Next visual integration

The initial static leaf-receiver implementation and checks are now documented in
LEAF_INDIRECT.md. The design requirements below remain relevant to its limits.
Leaf receivers need separate reflected and transmitted diffuse illumination.
Keep both demodulated fields separate from the RGB rho/tau preset until final
composition; folding transmission into lighting divided by dark albedo would
create unstable near-black values and mix material color into reconstruction.
Share geometric guides and scene invalidation, preserve separate radiance
histories, and do not multiply the traced result by vanilla sky/AO again.
Check front/back sky apertures, alpha holes, opaque blockers and an offscreen
emitter against source geometry/textures before accepting natural imagery.
The current cubical leaf representation and preset remain approximations;
multiple-bounce transport and exposure still require separate implementation
and high-sample reference work. The spatial filter cannot supply missing energy.

## Optional temporal accumulation checkpoint (2026-10-15)

`-IndirectTemporal` implies diffuse transport and motion inputs. Raw samples now
store demodulated diffuse lighting `(1-F)*Li`; direct composition multiplies the
current receiver albedo. This semantic applies to the raw path too. Sampling
varies by an integer frame serial only in temporal mode. No radiance clamp or
spatial filter is applied.

The frame buffer grows from 1120 to 1152 bytes, preserving existing offsets:
`RaySceneState` at 1120 contains the 64-bit scene epoch, pending section count
and uint frame serial. `RaySceneFlags` at 1136 records actual AS availability.
History requires consecutive frames, ready scene/AS, matching epoch, ray origin,
dimension and fog type, plus bounded sun/rain/solar changes. Scene changes
conservatively invalidate all history, even where the changed mesh is distant.

Reprojection converts the existing unjittered previous-minus-current motion into
coordinates of the actual jittered images. Per-pixel tests require valid motion,
in-bounds coordinates, matching material, normal dot above .98, plane separation
at most .02 blocks and a footprint-scaled distance capped at .25 blocks. These
are heuristics, not complete disocclusion or dynamic-object tracking.

The mean accumulates exactly up to count 32, then becomes a 1/32 EMA. It is not a
sliding window of 32 independent samples. Previous lighting/count, position,
normal and scene/environment metadata are retained. Lighting and guides cost
approximately 100 MiB in history pairs at 1707x960, plus the 12.5 MiB raw target.
Tiny metadata use separate passes because graph outputs must share dimensions.

Host `296959B7EFB04DE59E06D536E7E5D6CAE685C8FA37EB92E18A7FA4F5DCAF147A`
passes `qa-indirect-temporal-7`. Independent CPU recurrence/rejection uses actual
current/previous inputs; raw transport retains its separate source-triangle/PNG
oracle. Actual stored motion is an input to the recurrence oracle, while the
existing motion oracle checks that motion separately. Exact integer lookup
boundaries within .0002 pixels are excluded only when history could be used.
The unchanged numeric tolerance is .001 + .0015 * abs(expected), accounting for
FP16 output. No high-sample image reference or convergence claim is implied.

| Phase | Pixels checked | History accepted | At count 32 | Maximum count |
|---|---:|---:|---:|---:|
| Stable scene | 2155 | 2134 | 1599 | 32 |
| Camera translation, -0.4375 blocks X | 1876 | 1726 | 0 | 16 |
| Geometry/emitter change | 1210 | 0 | 0 | 1 |

Maximum recurrence RGB error respectively .000760675, .000486586 and .00319958
(the last contains larger HDR values). Motion excludes 14 boundary pixels; the
other two phases exclude none. Alpha/leaf, atmospheric lighting across 32 jitter
phases, reflection/removal, actual NGX SR, resize/reload and scene retirement
also pass; other mod hashes remain unchanged. Far-origin temporal operation,
feature-off regression on this host and a clean repeat still need verification.

Failed temporal runs are retained: run 1 violates equal output dimensions; runs
2-4 expose an oracle error that excluded history lookup boundaries even during
a global reset. Run 3 also adds an explicit AS-availability readiness condition,
which is a contract improvement but did not fix that oracle failure. Run 5 hits
ambiguous lookup boundaries in its steady capture. Run 6 first passes stable
32-count and scene-change checks; run 7 adds actual moving-camera coverage.
The steady capture now waits 64 ready frames with one epoch and an unambiguous
jitter phase. This strengthens cap coverage; it does not relax tolerances.

Full receiver support, entity/wind/water motion, spatial filtering, high-sample
convergence, paired natural cost and cinematic visual acceptance remain open.

`qa-indirect-temporal-natural-1` passes day/sunset execution on the same 296959B7
host. Its pictures still fail cinematic acceptance: dark canopies, extremely dark
sunset and fallback blue water. Different generated mesh contents and evolving
geometry prevent a quantitative raw-versus-temporal image/cost comparison.

A QA-only capture addition on host
`00AC271BB424E1A5316BE7A52F0C424906776677E219CF37B81D3FBEB1982378`
adds a post-timing histogram of the actual accumulated-light texture.
`qa-indirect-temporal-natural-2` passes both phases with unchanged other mods:

| One captured image | Day | Sunset |
|---|---:|---:|
| Supported receiver pixels | 495694 | 495249 |
| Mean stored count | 22.40982 | 22.46108 |
| Fraction at count 32 | .509413 | .508284 |
| Fraction at count 1 | .055853 | .028004 |
| Timed intervals | 5842 | 4894 |
| Mean / p95 / p99 wall ms | 3.42292 / 4.2596 / 6.3837 | 4.08715 / 4.8437 / 6.8440 |

Unsupported pixels carry count zero. These histograms establish that history
survives in the natural scene at capture time; they do not measure variance,
convergence or stability over the entire window. Both windows have changing
geometry and no throttle, at eight chunks. Readbacks start after timing ends.
Wall cadence includes the CPU camera guard and is not GPU pass cost or proof
of finished-product performance. The visual problems above remain unresolved.

The same 00AC271B host passes `qa-indirect-temporal-raw-fallback-1` (new
demodulated raw semantics with temporal disabled),
`qa-indirect-temporal-feature-off-1` (previous lighting/material/atlas path),
and `qa-indirect-temporal-far-1` (near temporal oracles plus existing far
reflection/NGX/rebase-return checks). The latter does not independently compare
accumulation at distant coordinates: its temporal oracle runs in the near fixture.
All other mod hashes remain unchanged. Results and failed attempts are archived
under their exact run names, with generated pack manifests and file hashes.
The subsequent clean offline local JAR/native rebuild matches those tested
hashes and the CPU surface/payload contract passes. The record is
`evidence/indirect-temporal-clean-rebuild.json`. This is local reproducibility,
not cross-machine verification or resolution of original-source/SDK licensing.

## Visible and atmospheric transport checkpoint (2026-10-15)

This historical checkpoint precedes temporal accumulation described below.

`Create-RayLightingPack.ps1 -DiffuseIndirect` enables the experimental path;
default generation retains the previous lighting. The shared integrator is
`examples/ray-lighting/indirect.comp`. Without diagnostic outputs it writes one
RGBA16F target, approximately 12.50 MiB at 1707x960. The four FP32 history pairs
described in the earlier checkpoint are only requested by `-IndirectDiagnostic`.
The history allocation still means readback lifetime, not accumulation.

For covered diffuse receivers, `ray.direct` replaces the old sky fill and the
approximate block-light component with the traced contribution. Material emission
is preserved through the existing explicit class presets, separate from that
fill. Baked AO/skylight are not multiplied into the traced estimate. Unsupported
thin vegetation/metals/translucencies retain the previous receiver policy.
Actual source emission still uses the current class presets, not calibrated
LabPBR emission maps or a complete dynamic-light system.

With `-Atmosphere`, misses sample the shared sky LUT without the solar disk.
Explicit solar next-event evaluation already accounts for that source. Secondary
hits use the shared solar lighting texel and the existing short-path aerial model
for the receiver-to-hit segment. The final direct pass applies the camera segment
once. This inherits the existing observer-density/height approximation; it is not
full volumetric or multiple-scattering transport. Fallback sky remains supported.

`qa-indirect-atmosphere-1` first passes the raw atmospheric reference on JAR
427E5B5170F2F16FC3C265B4D52CC2A87C9CFF66F9CB54D253B71E01938ED022.
CPU comparison uses independent source-triangle/PNG hit lighting, independent
double aerial math and independent interpolation of the shared LUT input. The
LUT/solar inputs are separately checked against the atmosphere quadrature in the
same world lifecycle. This is not a new full physical sky integration per ray.

`qa-indirect-visible-1` passes actual HDR replacement on JAR
C08CAE14D15728DEC7A27F5D6A32B0D2E59886B96C0BAE402B203F7E1E2AEEDC.
The reference computes both raw contribution and final primary HDR from CPU
transport, including independent primary shadows, solar BRDF, source emission
and the camera aerial segment. It does not use the GPU raw contribution as its
reference for that final sampled-pixel comparison. The separate 32-phase quartz
check also verifies composition using the already independently tested input.

| Visible HDR CPU/source comparison | Emitter present | Emitter removed |
|---|---:|---:|
| Accepted samples | 841 | 478 |
| Sky misses / secondary hits | 783 / 58 | 452 / 26 |
| Hits on the wholly offscreen emitter | 20 | 0 |
| Maximum incoming RGB error | 9.22292e-6 | 3.47910e-7 |
| Maximum receiver contribution error | 2.07808e-6 | 2.90363e-7 |
| Maximum visible HDR error | 0.00368558 | 0.00340586 |

The existing alpha/leaf, day/sunset atmosphere, 32-phase lighting, reflections,
NGX SR, motion, resize/reload and retirement gates also pass in that run. The
other three mod binaries retain their hashes. A failed intermediate compiler
attempt (local name collision in the oracle) is preserved separately.

The first natural one-target FP16 run captures day but fails the sunset camera
gate and remains `passed=false`. Its day image has severe visible speckle and
dark canopy interiors: cinematic acceptance is rejected. The old pose check
compared a projection-dependent center ray to an unjittered commanded direction.
The revised check compares the actual inverse-view negative-Z axis instead and
records the projection-direction offset separately, with the same 0.002 radian
rotation limit. This distinguishes camera rotation from projection displacement;
it does not by itself establish the cause of that failed run. Its raw evidence
is preserved unchanged. Do not use the partial run as successful day/sunset or
paired performance evidence.

`qa-indirect-natural-2` passes both natural phases on host
FB605CA4955676047E466218CB74ECB95CA51F7BD354A1DD13BBB7AAF0871923.
It uses the actual one-output FP16 path, with no transport diagnostic readbacks
during measurement and no diagnostic FP32 image pairs. The inverse-view camera
axis is checked every measured frame; peak rotation error is 0.000107467 radians
and eye error 1.1921e-7 m. Projection-direction offset is recorded separately
(maximum observed in this run approximately 0.000988651 radians).

| Natural wall cadence, eight chunks, 20 seconds | Day | Sunset |
|---|---:|---:|
| Frame intervals | 5452 | 4691 |
| Mean | 3.66770 ms | 4.26382 ms |
| p95 | 4.6451 ms | 5.0648 ms |
| p99 | 7.4065 ms | 7.9225 ms |

Both windows report no throttle and changing geometry. These are wall intervals
of an unfinished one-sample renderer, not GPU pass timings, paired before/after
costs or proof of the full 100 FPS product target. The fresh generated world is
not identical to earlier captures. Day has severe speckle; sunset is excessively
dark; canopy interiors, leaf-receiver lighting and water remain unconvincing.
The raw screenshots are retained unchanged and visual acceptance is rejected.

The same final host FB605CA4…871923 passes `qa-indirect-visible-2` (visible HDR,
shared atmosphere, 32 jitter phases, far-coordinate reflection/DLSS, removal,
reload/resize and lifetime), `qa-indirect-visible-fallback-1` (visible HDR with
the fallback sky), and `qa-indirect-visible-feature-off-1` (legacy material/atlas
and direct-light reference). The non-Sulkan mod hashes remain unchanged in each.
Raw results, pack inputs, failed attempts and screenshots are archived separately.
The clean local offline JAR/native rebuild reproduces the tested hashes; the
CPU surface serialization contract passes. Logs and
`evidence/indirect-visible-clean-rebuild.json` retain the comparison. Independent
checkout reproduction and complete release provenance/licensing remain open.

Temporal sampling/accumulation, disocclusion and scene-change rejection, spatial
filtering, complete receiver/motion support and a convincing cinematic scene
remain required. No finished quality or 100 FPS claim follows from these checks.

## Earlier raw transport checkpoint (2026-10-15)

The following section records the pre-composition checkpoint. Its statements
about missing atmospheric/visible integration describe that earlier state.

`Create-RayLightingPack.ps1 -IndirectDiagnostic` now emits a deterministic
one-bounce GPU pass using the existing terrain acceleration structures and atlas.
It traces one cosine-weighted direction per supported diffuse receiver pixel.
Static thin vegetation and metals are excluded as receivers; alpha-tested leaf
surfaces remain eligible as secondary hits. Glass/water are excluded by the
existing terrain ray mask. No new device, queue or mesher is introduced.

The pass writes incoming radiance, material/hit distance/position, direction/PDF
and receiver contribution. Source emission and shadowed sunlight are the only
secondary surface terms; no baked AO, vanilla skylight or approximate block-light
fill is injected into the bounce. Primary and raw diffuse-bounce secondary sunlight
share the new `surface-lighting.glsl` GGX function. The earlier smooth-mirror
secondary shading still has its documented simpler approximation. Optional leaf transmission uses the current
bounded leaf preset. The sky is the explicit fallback environment already used
by the non-atmospheric reflection fixture; this diagnostic currently rejects
the Atmosphere option rather than silently claiming shared sky integration.

The raw pass does not yet replace visible ambient fill or enter the DLSS color
input. It is a transport diagnostic, with no temporal history, filtering or new
gameplay-visible GI. Its graph images have `history=true` solely to prevent
aliasing and preserve the last rendered image for asynchronous readback; that
flag does not mean temporal accumulation is implemented. Four RGBA32F image
pairs cost about 200 MiB at 1707x960, deliberately a diagnostic configuration.
The final production resources/sample budget must be designed separately.

`Run-QaWorld.ps1 -RayLighting -Reflections -AlphaTraversal -WorldDlss
-FoliageTransmission -IndirectDiagnostic -NativeLibrary <native-library>
-RuntimeDirectory <external-runtime> ...` runs the controlled test. It requires
the reflection fixture so an emitter can be independently proven wholly outside
the actual camera frustum. `IndirectConformance` compares GPU directions, PDF,
hit/miss, distance, material, radiance and receiver contribution against double
CPU triangle intersections and original PNG texture samples. It samples source
texels, not GPU-readback material color, to establish secondary hit lighting.

CPU texture coverage includes stationary block PNG sprites actually registered
in the atlas. Animated/unsupported sources fail if intersected. The alpha oracle
still explicitly supports stationary oak cutouts. This does not establish general
pack/animation compatibility. CPU-selected texel boundaries and independently
ambiguous opposing closest faces are counted and excluded; numeric tolerances
were not loosened. All four GPU outputs are scanned for nonfinite values.

Run `qa-indirect-raw-5` passes on JAR SHA256
`2F894E44E88E7BA0D37E619019C5ECFEFED4D8C8A3BA7F345DDC779BBD949595`.
The three non-Sulkan mods retain their hashes. The existing native adapter is
unchanged. This test also passes the leaf reference, alpha traversal, smooth
reflections/removal, NGX SR, motion/jitter, resize/reload and resource retirement.

| Compared raw samples | Emitter present | Emitter removed |
|---|---:|---:|
| Accepted samples | 854 | 430 |
| Sky misses / surface hits | 795 / 59 | 407 / 23 |
| Emissive surface hits | 20 | 0 |
| Hits on the wholly offscreen ceiling emitter | 18 | 0 |
| Sunlit / shadowed secondary hits | 37 / 0 | 15 / 5 |
| Source-texel boundary exclusions | 11 | 2 |
| Opposed closest-face exclusions | 1 | 3 |
| Maximum direction component error | 6.46493e-7 | 6.44906e-7 |
| Maximum distance error, blocks | 8.43378e-5 | 3.71075e-5 |
| Maximum incoming RGB error | 9.69869e-6 | 1.52532e-7 |
| Maximum receiver RGB contribution error | 4.91844e-6 | 4.41388e-7 |

The second phase runs after camera-motion/resize checks, so these are separate
CPU-reference comparisons, not a pixel-paired subtraction or a history-rejection
test. No source-image editing or completed-performance inference is involved.

Retained failures: the first compile attempt used an invalid compound Java `var`
declaration. Raw run 1 encountered a source sprite missing from the original
small fixture texture list; the reference now loads registered stationary block
sprites from source resources. Runs 2/3 caught diagnostic images being reused
after their last graph consumer, producing overwritten readbacks. Declaring
their full readback lifetime fixed that discrepancy without changing traversal
or relaxing comparison tolerances. Run 4 first passes raw radiance; run 5 adds
explicit offscreen-emitter coverage and the removal reference.

The first full atmosphere regression later failed its existing lighting coverage
gate after five jitter phases (zero quartz/emission samples). Its raw result is
retained as failed, not counted as a renderer pass. The synthetic QA controller
now releases mouse capture, clears movement keys and stops client free-flight
velocity throughout its verified disposable world, including pending readbacks;
scripted server teleports remain active. Coverage errors also report actual camera
position/direction. This reduces unintended input as a test variable; it does not
prove the precise cause of that historical failure. Regression atmosphere run 2
passes all 32 jitter phases, day/sunset atmosphere, leaf, far-coordinate reflection
and DLSS, motion, resize/reload and retirement on JAR
`92B6B67E5AB0580A953682E847295A71C42566C314AC832A97BD13E25256C17F`.
That regression runs with raw indirect disabled, so it proves preservation of
the existing paths, not atmospheric indirect transport.

On that same newer hash, `qa-indirect-raw-6` repeats the raw reference and full
non-atmospheric lifecycle. Present/removed counts are 862/440 accepted samples,
66/28 surface hits, 796/412 sky misses, 21/0 emissive hits and 19/0 hits on the
wholly offscreen emitter. Maximum incoming RGB errors are 1.21719e-5 / 2.48977e-7;
receiver-contribution errors are 3.13762e-6 / 1.90772e-7. This confirms the QA
input guard does not substitute for the independent transport comparison.

`qa-indirect-feature-off-2` also passes on the same hash, including 657 quartz
lighting/material samples, nonunity AO coverage, live atlas and reload. A clean
local offline build reproduces that JAR exactly and the unchanged native hash
F089BA5D…45257; the CPU ray-surface serialization contract passes. Build/native
logs and `evidence/indirect-clean-rebuild.json` are retained. This is local
reproduction, not yet an independent checkout or a finished release.

Next: feed a filtered/accumulated estimate into visible HDR while replacing the
overlapping sky/block fill for supported receivers, integrate the shared
atmosphere, and implement the rejection and receiver policies below. A raw
transport pass is insufficient to offer a usable cinematic GI mode.

## Transport contract

Keep Minecraft's device/queue, Sodium capture, incremental BLAS/TLAS, paged hit
attributes and NGX SR. Add optional shaderpack passes, default off. Their outputs
must feed the existing world HDR before display/NGX. Do not add another device,
mesher, swapchain or shader compiler to obtain a diffuse bounce.

Trace a cosine-weighted hemisphere at the raster surface using the actual
geometric/shading normal contract and camera-relative origin. Recover hit
position in the ray coordinate system; `SulkanRaySurface.position` itself is
section-local and must not be treated as a scene position. Ray origins require
the same precision/self-intersection policy as the existing shadow/reflection
paths. Cutouts use the real atlas and material threshold. Geometry outside the
screen remains eligible through the existing scene acceleration structures.

The first transport estimator is one diffuse interaction at the receiver and
direct sunlight/emission at the secondary surface, or sky radiance on a miss.
The secondary solar ray uses actual visibility, including the current optional
leaf shadow model. It must not reintroduce vanilla ambient/block-light fill as
physical hit radiance. If approximate fill is retained in an experimental mode,
label it separately and exclude it from the physical reference comparison.
Use source pre-AO albedo and material emission. The sample estimate is
f_receiver * Li * abs(n dot wi) / pdf(wi), in scene-linear HDR. Do not multiply
by baked AO/skylight again when traced visibility replaces that approximation.

The existing sky fill and the sampled sky path represent overlapping energy.
For an enabled/covered receiver, replace the old fill with the estimator rather
than adding both. Preserve the old fill only for explicitly unsupported surfaces
or feature-off behavior. Leaf transmission, metallic surfaces and water need
explicit receiver policies; silently treating them as ordinary diffuse albedo
is not accepted. Start with a documented diffuse receiver subset, then extend
it before calling global illumination complete.

Primary/secondary direct radiance must share material definitions and solar
intensity. Prefer a common GLSL lighting helper over copying another divergent
BRDF. Keep primary camera-path aerial effects applied once. Secondary-path
atmospheric radiance/attenuation must have a stated distance and miss policy.

## Sampling and reconstruction

An optional raw diagnostic must expose the incoming radiance, selected ray,
hit/miss, hit distance and estimator contribution independently of display.
Fix the sample sequence/seed for CPU comparisons; enable frame variation only
after deterministic checks pass. Report the actual sample count and ray budget.
A noisy raw frame is not a user-ready rendering mode.

Temporal accumulation must reproject in the same unjittered pixel convention
as the existing motion contract. Track geometry/material generation, current
and previous surface identifiers, normal, depth/position, sample count and
moments. Reject disocclusion, invalid motion, changed geometry/materials,
camera cuts/rebases, resize, reload and renderer-generation changes. No history
is valid merely because a reprojected coordinate lies inside the image.
Frame-to-frame sampling must not change the CPU oracle's declared sequence.
Dynamic entities, wind and water cannot inherit a camera-only motion claim.

Filtering preserves discontinuities in geometry/material, separates illumination
from base color where appropriate, and bounds history without claiming an
uncalibrated luminance clamp is unbiased. Every clamp and dropped-sample policy
must be recorded. DLSS SR consumes the resulting HDR; it does not replace this
denoising stage. RR is a later separately checked backend with matching guides.

## Acceptance and evidence

1. Independent double-precision CPU triangles and original PNG texels verify
   selected GPU bounce directions, distances, decoded materials and radiance.
   The reference may not use the GPU hit as its own visibility oracle.
2. A controlled colored wall and occluder demonstrate a secondary contribution
   from geometry outside the screen. Moving/removing that geometry changes the
   computed contribution and rejects stale history. A sky aperture verifies
   visibility and no duplicated old sky fill.
3. Include front/back faces, alpha holes, opaque blockers, emissive surfaces,
   normal changes, invalid inputs and far coordinates. Record any deliberately
   excluded boundary samples and require actual tested sample coverage.
4. Check accumulation against deterministic repeated samples and an independent
   multi-sample reference. Camera motion/disocclusion must not leave stale light,
   smear edges or produce visible shaking. A static screenshot cannot prove this.
5. Repeat feature-off lighting, leaf, reflection, atlas/reload, DLSS resize and
   GPU lifetime checks. Keep failed attempts and do not loosen tolerances simply
   to make a run pass.
6. Capture the guarded natural forest/river day/sunset and an interior. Measure
   the cost with the same saved geometry/pose and explicit sample/filter settings.
   Record GPU timings if added; wall cadence alone cannot isolate pass cost.
   1440p / approximately 100 FPS and Photon visual quality remain full-product
   gates, including unfinished water/weather/rough reflections and complete motion.

## Reference reuse

REUSE_AUDIT.md identifies fixed Caustica/MCVR/vk_streamline versions. Their
material, scene lifetime, guide preparation and denoising organization can inform
this implementation. Translating source requires per-file origin/license records.
No foreign code is imported by this design. Preserve working NGX SR; do not make
FG or a complete RT-pipeline migration prerequisites for the first diffuse bounce.
