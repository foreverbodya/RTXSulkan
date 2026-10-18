# Sulkan cinematic development

Status: reconstructed baseline builds with Gradle and renders disposable worlds;
hardware ray queries intersect captured terrain correctly; no cinematic release yet.

## Scope

Target Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0+26.3,
Sodium 0.9.2+mc26.3, Java 25, Windows, NVIDIA RTX 5070.
Changes belong to Sulkan. Preserve all other installed mod binaries.

User priority updated on 2026-10-16: maximize cinematic RTX image quality at
2560 × 1440 on the RTX 5070. There is no FPS acceptance target; the earlier
60/100 FPS targets are withdrawn. Defer further DLSS development and performance
optimization until the visual result is accepted. Preserve existing working
integration and historical measurements without treating them as release gates.

Current visual order:
1. Correct composition and stability: held items, sky, transparent surfaces and
   camera movement. Keep builtin-pack reports distinct from experimental-pack bugs.
2. Establish a convincing forest/river daylight scene: balanced diffuse transport,
   foliage, physically consistent materials, sky and water.
3. Extend the accepted look to sunset, interiors, night and weather; validate
   moving geometry and temporal stability in representative gameplay.
4. Revisit DLSS quality and optimization after visual acceptance.

## Baseline provenance

- Public upstream: https://github.com/mravatins/sulkanShaders
- Public commit: c588577a53dc70bc5d6ef3999ec7930ff6b6ff77 (version 0.3.1, Minecraft 26.2).
- Official 0.5.1 artifact: https://cdn.modrinth.com/data/jqg5mkGh/versions/o5nFa0hw/sulkan-0.5.1-26.3.jar
- Artifact SHA-1: a0a687fdc0a33539a17f51972415c90121484771.
- Java sources reconstructed from the official 0.5.1 artifact with Vineflower
  1.12.0. These are not the author's original sources. Compilation, startup,
  and synthetic ray-query execution are verified. Full runtime equivalence
  has not yet been established.
- Assets and bundled shader resources copied from that same artifact.
- Build scaffolding and GPL-3.0 license retained from public upstream.
- Website pack: https://sulkan.org/downloads/0.5.1/Sulkan-Realistic.zip

Read-only upstream recheck on 2026-10-16 still finds commit c588577a..., one
public branch (main), no tags, and mod_version=0.3.1 in gradle.properties.
The website continues to advertise the real 0.5.1 / 26.3 runtime. These are
different artifacts; original author source for 0.5.1 remains unestablished.
See evidence/upstream-source-recheck-20261016.json. Reconstructed sources remain
explicitly labeled and are not claimed to be the author's original files.

## Work queue and acceptance gates

Latest horizon checkpoint: host 38EE8074...B87AC9C4 adds an opt-in distant-world
boundary pass. Six 0/1/0 static captures show the grey band removed; all disabled
pixels and zero-weight pixels copy exactly at the HDR pass. Separate FP32 blend
and legal FP16-format-neighbor checks, offline replay and corruption controls
pass. The earlier failed nearest-assumption checker/capture remains a failure;
this is an explicit v2 validation policy revision, documented in DISTANT_WORLD.md.
The same host passes combined cloud motion/adjacent capture. Full temporal,
weather and cinematic acceptance stay open. The earlier preserve-tint 2/8/2
comparison yields modest light improvement, not a finished forest. Defaults
and the installed user preview are unchanged.

Latest material/compiler checkpoint: host 33779563...3A0420F1 adds explicit
experimental foliage absorption variants while keeping default 0. Preserve-tint
mode 2 passes a six-capture 0/2/0 frozen-world comparison, source-triangle/PNG
transport and ordinary synthetic lifecycle checks, and combined cloud motion.
Source-light changes are separately measured before exposure; the brighter
leaves also change metering. This is not full forest/cinematic acceptance.
See LEAF_OPTICS.md. The actual LWJGL source-string stack exhaustion is repaired
using explicit finally-freed native UTF-8 allocations; the same 1.24 MB shaderc
probe fails on the retained old JAR and passes on the new one. See
NATIVE_SHADER_COMPILATION.md. No other mod or user-instance artifact changed.

Current motion checkpoint: CLOUD_RECONSTRUCTION.md records separate optional
finite-cloud depth/motion inputs to the existing native reconstruction path.
The original 406-frame audit/raw-input replays pass; two newer 601-frame runs
also capture 24 adjacent frames per guide mode with actual matrix/history checks.
Actual poses/cloud inputs match; jitter and terrain are not fully matched. This
is integration/capture evidence, not temporal aesthetic acceptance. A separate
source-density study identified coarse view sampling as a material error source.
Refined interval density now has six frozen-view 0/1/0 captures and source/math
replay evidence on A6D08D0E...A5DE6C02; see CLOUD_SAMPLING.md. Visible change is
modest, not cinematic acceptance. The combined refined-density/finite-guide run
qa-cloud-column-motion-1 passes 601 path frames and a 24-frame adjacent burst;
all 23 matrix/history relationships are valid. This is scoped execution evidence,
not temporal visual acceptance. See cloud-column-motion-status-1.json. Clouds
remain too soft; guides default off, the user instance unchanged, and further
DLSS features/optimization deferred. The full synthetic grazing-water failure
and original-source/license/release gates remain open.

Raw GPU buffers, QA images/animations and local source checkpoint ZIPs under
evidence are kept on disk but excluded from Git. Generated caches are excluded
too. Source/example assets, diagnostics, reports and hashes remain available.
A compact public validation bundle still needs curation before GitHub release;
ignoring local replay payloads is not deletion or a claim of release readiness.

Latest visual checkpoint: LIGHTING_AUDIT.md records actual pre-display components
in the fixed forest. Shaded samples retain nonzero traced illumination. Optional
photographic mode 2 makes the inspected day/sunset shadows more readable without
changing transport. Same-frame arithmetic and a full integrated fixture pass on
C6B4169B…07925FDE; see TONE_MAPPING.md. This is a limited display improvement,
not cinematic acceptance. The default remains the historical diagnostic mode.
The opt-in static HDR cloud prototype now has a 128-block 3D density profile,
approximate light-ray scattering orders and world solar shadows. Source-density,
composition and 8,192 receiver checks per day/sunset phase pass in
qa-cloud-shadow-receivers-2 on BB351A17…ABA5EE5C. Its visible detail remains too
smooth and is visually rejected. See CLOUD_DESIGN.md for exact scope, pack hashes,
retained failures and older cloud-disabled regression. Next improve cloud detail/
internal lighting and motion/depth contracts, then assess water/material richness.
Further DLSS work and optimization remain
deferred; there is no FPS acceptance target.

The later cellular cloud profile passes PNG/density/period and static shadow
checks in qa-cloud-cellular-2. A real-game settled translation/rotation/return
audit passes on 9796A949…7784476; raw world maps stay byte-identical and the
environment returns exactly. This is not continuous motion or advection quality.
The latest full cloud-disabled regression fails the retained water primary/source
boundary case; its replay and original failure remain open. Clean artifact and
pack/noise reproduction are recorded in CLOUD_DESIGN.md. Do not call the current
state a release or all-green regression. Next improve cloud light and investigate
that gate before claiming movement stability.
Preserve the open leaf-model and gameplay correctness limits.

Current sampling checkpoint: selectable 1/4/16/64 raw paths are implemented,
with four-path means passing independent source-based transport checks on
7519E663…1121CB9. A later grazing-direction correction passes an explicit GPU
endpoint check, full single-path/legacy regression and full four-path/AgX run
on 6037801D…09C80994. The older same-world 1/16/1 day/sunset comparison retains exact geometry
and remains visually rejected: increasing samples does not resolve dark canopy.
See INDIRECT_SAMPLING.md for scope, exclusions and the next leaf/material audit.
The default remains 1; no high-sample convergence claim is made.

Current display checkpoint: optional AgX and same-frame GPU display comparison
pass scoped day/sunset arithmetic checks on 24D72B98…A1DF12D. See TONE_MAPPING.md.
Daytime greens are less saturated, but canopy darkness remains visually rejected.
Keep the legacy default until wider visual assessment; integrated mode-1
arithmetic now passes in the four-path fixture. Next prioritize canopy illumination and leaf response, followed by
water/reflections and atmosphere; no FPS target or further DLSS work governs
this visual milestone.

Latest atmosphere work: optional isotropic multiple scattering is implemented
from the author's MIT reference and passes scoped CPU/GPU checks and matched
0/1/0 day/sunset comparison. See MULTIPLE_SCATTERING.md. The new contribution
does not complete the look. Two rejected regression attempts remain open:
subpixel water coverage and a secondary-ray distance discrepancy. A subsequent
full run on B2B6F09E…F37FFCE9 passes without reproducing either; that does not
close them. Next distinguish leaf response and display shadow compression from
insufficient illumination, with matched and adequately sampled references.

Latest visual investigation: optional bounded diffuse depth through 8 passes
source/GPU full-path radiance and integrated checks on 970723C8…13860D26;
the previous two-depth path also passes its full regression. A matched 2/8/2
forest comparison retains exact geometry and shows only a small stochastic HDR
difference, with dark canopy still visually rejected. See DEEP_DIFFUSE_PATH.md.
Next, assess the sky/leaf/display model against a sufficiently sampled reference;
do not treat increased depth alone as the missing cinematic look.

The optional second diffuse vertex passes CPU/source-PNG transport and integrated
world checks on 712074B5…71B1054, with clean local JAR/sources/native hash repeat
and one-bounce/no-water regression. Natural day/sunset means are 8.063/10.824 ms
at eight chunks; image quality still fails. See SECOND_DIFFUSE_BOUNCE.md. The
next useful visual/cost comparison must keep the actual loaded scene fixed;
the independent natural generations differ and cannot isolate this feature.

Static above-water HDR reflection/refraction now passes the integrated
`qa-water-hdr-7` fixture on 22EF8935…BE64C5, including independent source-based
water optics/color and camera motion. See WATER_HDR.md for exact scope and
retained failures. Natural visual acceptance, animated water and underwater
media are still open; the fixture does not establish completed-product FPS.

Visible diffuse transport follow-up: `-DiffuseIndirect` replaces approximate
ambient/block fill for covered receivers before NGX SR. It shares atmospheric
sky/solar inputs and separates source emission from the old fill. The C08CAE14…
2AEEDC fixture passes independent CPU/source-PNG transport and final HDR checks.
The later FB605CA4…871923 natural run passes actual camera-axis checks at day and
sunset using the one-target FP16 path, but visible noise and darkness reject the
cinematic target. A later optional temporal path passes recurrence/rejection at
rest, during camera translation and on a scene change (296959B7…F147A).
Optional spatial reconstruction now passes three-pass CPU arithmetic and natural
execution (73F4A63F…85BEFD), while its pictures still fail visual acceptance.
Convergence, full receiver/motion support,
water and exposure development remain required; see INDIRECT_LIGHTING.md.

Earlier raw diffuse checkpoint: an optional one-sample ray-query pass and a
shared primary/secondary solar GGX helper are implemented. Independent source
CPU/PNG comparisons prove offscreen emitter contribution and its removal on
2F894E44…49595; this remains a diagnostic with no visible HDR replacement,
atmospheric integration or accumulation/filtering. Final QA-input-guard host
92B6B67E…6C17F passes raw run 6, feature-off run 2 and full atmosphere regression
run 2; clean local JAR/native rebuild hashes match. See INDIRECT_LIGHTING.md.

Earlier leaf follow-up: optional colored leaf transmission passes the integrated,
feature-off and surface GPU checks on JAR DA77258A…97509. Natural camera pose is
now checked against actual shader uniforms every measured frame; older natural
captures cannot support verified fixed-view comparisons. See
FOLIAGE_TRANSMISSION.md for contracts, retained failures, scope and measurements.
Optional static leaf receivers now pass raw front/back source transport,
separate history/filter and source-based camera motion checks; see
LEAF_INDIRECT.md. Wind is pinned off in that mode. The next visual work is
canopy energy and exposure, alongside a matched high-sample reference;
optional post-SR histogram exposure now passes source metering and actual
display checks (AUTO_EXPOSURE.md). Its natural images expose overbright fallback
water and remaining canopy darkness. Water HDR/optics, additional transport and
transition stability remain visual work; passing arithmetic is not acceptance.
canopy interiors and water still fail the visual target.

### Accepted visual direction (2026-10-14)

Build a standalone cinematic pack with Photon as the image-quality reference,
using Sulkan as the Vulkan host. The builtin Sulkan Realistic appearance is no
longer the product baseline. Engine geometry, graph and resource facilities are
shared; the pack owns its lighting, atmosphere, clouds, water and presentation.
The RayLighting pack remains an engineering fixture, not the product preview.

Latest checkpoint: optional shared clear-sky atmosphere, hardware shadows,
smooth-metal reflections, alpha traversal, camera-relative rays and actual DLSS
Quality pass the integrated diagnostic described in ATMOSPHERE.md. Ordinary
no-atmosphere and live-atlas/reload regressions pass after correcting a missing
conditional include in the pack generator. A clean local JAR/native rebuild
reproduces the tested hashes. The next visual milestone remains a representative
forest/river scene: assess existing lighting and measure costs before claiming
Photon-level quality. Clouds, water optics, indirect light, rough reflections,
dynamic motion and independent-checkout reproduction remain open.

Natural-world follow-up: the eight-chunk forest/river scene exposed exhaustion
of the old 64 MiB attribute arena. The replacement uses a stable address directory
and bounded on-demand pages; the scene now renders, and GPU/integrated checks pass
(PAGED_ATTRIBUTES.md). Captured day/sunset images fail the cinematic visual target:
canopy shading is too dark and water still follows its fallback. NATURAL_WORLD.md
records the exact scene and limited wall-cadence measurements. Next: preserve
base tint separately from Sodium's baked AO at the existing encoder hook, then
validate consistent raster/ray materials before indirect lighting and water work.

Material follow-up: pre-AO tint and original baked brightness are now separate
32-byte source fields, with unchanged legacy baked color. Ray payload size stays
32 bytes per vertex. Cross-page GPU, encoder bounds/lifetime, three-way live-atlas
AO and full integrated tests pass; see MATERIAL_COLOR.md. Sunlight and emission
no longer use the baked tint. The forest still lacks convincing indirect light
and foliage transmission, and water remains a separate unfinished task.

Current implementation order:

1. Connect traced hit attributes and the live block atlas to the regular render
   graph. Compare traced material samples with rasterized surfaces before using
   them for reflected radiance. Verify reload and GPU retirement.
2. Audit the DLSS Vulkan SDK path and record integration prerequisites early:
   native build, device negotiation, image bindings, motion, jitter, HDR exposure
   and resets. Verify initialization before calling DLSS available.
3. Build one complete daylight scene in the standalone pack: consistent sky,
   exposure, direct and sky lighting, materials and water. Retain fixed camera
   references for forest, river and interior scenes; assess image quality before
   offering it as a gameplay preview.
4. Add hardware reflections and indirect lighting with material-aware traversal,
   denoising and camera-motion validation. Alpha cutouts and dynamic geometry
   require separate acceptance checks.
5. Complete weather, night, entities, transparencies and other dimensions; then
   revisit DLSS Quality and performance optimization after visual acceptance.
6. Clean-checkout reproducibility, extended gameplay checks, license review,
   documentation, source provenance and GitHub release.

Progress on 2026-10-15: the external native NGX probe compiles, initializes on
Minecraft's device and reports DLSS available. Its optimal Quality dimensions
for 1440p are 1707x960. DLSS and ray-query feature sets negotiate together, and
the existing world fixture passes with that combined device feature set. The
capability-only probe does not create or evaluate a reconstruction feature.
The subsequent session adapter now does: four constant HDR GPU frames and
retirement pass with release runtime and complete RGB readback. Next: world
motion/jitter, lower-resolution rendering and more demanding temporal tests.
The standalone visual pack is still unfinished.

The older checklist below records the original research sequence. The accepted
order above governs product work. Successful geometry diagnostics do not
establish visual quality; the user screenshots show the direct-lighting fixture
is visibly less complete than the builtin pack.

1. Recover a compilable 0.5.1 baseline. Resolve reconstruction artifacts with
   bytecode evidence; retain a record of substantive changes. Clean checkout
   must build with documented prerequisites.
2. Establish runtime baseline on the fixed modpack. Confirm actual Sulkan
   loading, scene rendering, resource reload, resize, and safe shutdown.
3. Probe device creation, extension/feature support and enabling, command
   submission, scene geometry access, HDR formats, and motion data. Record
   supported, enabled, missing, and unverified separately.
4. Establish scene/material/motion contracts and history ownership. Include
   debug views for depth, normal, roughness, material IDs, and motion.
5. Build cinematic HDR lighting and temporal stability; measure GPU time and
   memory on repeatable scenes before adding reconstruction.
6. Integrate DLSS SR/DLAA through an isolated native SDK adapter. Correct
   motion, depth conventions, jitter, exposure, resets, and shutdown are gates.
7. Implement hardware ray queries, scene acceleration structures, reflection
   visibility and material evaluation. Test chunk and dynamic geometry changes.
8. Add indirect lighting and denoising; assess Ray Reconstruction separately.
   Frame generation and full path tracing remain later, separately gated work.
9. Release candidate: clean build, licenses, install instructions, support
   matrix, performance measurements, known limits, and GitHub-ready sources.

## Evidence policy

Compilation is not GPU validation. Each claimed feature requires an actual
execution record. Screenshots alone do not establish temporal correctness.
Record failures and skipped checks; never label an unrun check as passed.
User-requested audio policy: agent-controlled disposable Minecraft launches
must set soundCategory_master:0.0 before launch. Both QA launchers do so.
No changes to installed non-Sulkan mods. Do not distribute proprietary SDK
components until their redistribution terms have been checked.

## Initial findings

The supplied crash log does not list Sulkan among Fabric-loaded mods.
At inspection time the instance contains `.disabled` and `.duplicate` Sulkan
files. The semaphore timeout near GUI blur is not sufficient to attribute the
failure to Sulkan. Separate disposable launches now verify baseline execution.

Minecraft 26.3 exposes the VkDevice and command submission through its Vulkan
backend. Optional ray-query enablement and BLAS/TLAS traversal now pass on this
device. Indexed opaque terrain intersections and chunk rebuilds also pass in
the QA fixture. Incremental GPU scene lifetime, materials and visible ray
lighting remain rendering gates; no DLSS or 100 FPS claim has been established.

Compute passes can now request an explicit ray-scene descriptor. A separate
diagnostic pack reconstructs surfaces from raster depth and displays a hardware
sun-visibility mask during normal frames. This connects the scene to the image
and now feeds an experimental deferred HDR direct-lighting pack. Its quartz
fixture pixels agree with independent CPU shadows/BRDF; the complete cinematic
lighting/material model is unfinished. Full-world allocation
pooling, camera-relative coordinates, alpha-aware traversal and denoising remain
required before production release.
