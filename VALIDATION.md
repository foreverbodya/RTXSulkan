# Validation ledger

## Atmospheric multiple scattering (2026-10-16)

[MULTIPLE_SCATTERING.md](MULTIPLE_SCATTERING.md) records the MIT adaptation,
20-cell independent closure checks, sky/lighting integration and a matched 0/1/0
forest comparison. The final diagnostic-recording host B2B6F09E…F37FFCE9 passes
one full integrated run and one no-multiple-scattering regression. Two earlier rejected attempts remain open: water source
coverage at a projected subpixel edge and a second-ray distance discrepancy.
Acceptance was not relaxed. This is not general regression or cinematic acceptance.

## Bounded diffuse depth (2026-10-16)

[DEEP_DIFFUSE_PATH.md](DEEP_DIFFUSE_PATH.md) records optional depths through 8,
independent source/PNG full-path radiance checks and a same-world 2/8/2 forest
comparison on `970723C8C7C9BD70E0828FA41F5C2B10512F83F8929A3ECA83EB2D5813860D26`.
The full eight-depth fixture passes. The comparison preserves exact geometry
but does not establish a substantial visual improvement; dense canopy remains
too dark. FPS targets are withdrawn, and further DLSS/optimization is deferred.

## First-person item capture (2026-10-16)

[HELD_ITEM_CAPTURE.md](HELD_ITEM_CAPTURE.md) records a new guarded six-image
fixture and actual builtin/experimental runs. A compass is visible against
stone, foliage and water in the inspected static captures. The reported server
failure remains unreproduced and open; no composition fix or gameplay acceptance
is claimed. Two rejected camera-fixture attempts are retained. These runs prove
capture completion, not the ordinary transport/lifecycle gates.

## Sky camera motion (2026-10-16)

[SKY_MOTION.md](SKY_MOTION.md) records 2,688 CPU cases, actual GPU flow under
both camera yaw signs, and a full leaf/indirect/water regression pass on
`16DE922605E2A642A2D8F291ED6F847C61741B141CE189912C8D675E87690907`.
The earlier intermittent water source-miss failure remains unexplained despite
not recurring in this run. User screenshots after switching to builtin Sulkan
are not treated as proof that the experimental atmospheric path caused them.

## Additional diffuse vertex (2026-10-16)

[SECOND_DIFFUSE_BOUNCE.md](SECOND_DIFFUSE_BOUNCE.md) records integrated
source/PNG transport checks and natural execution for the optional two-bounce
path. Latest tested JAR: `712074B5FD1A420E6ED6B21A16BB4EA26EE3F76DE24576CBE1D5A469471B1054`.
Clean local JAR/sources/native hashes repeat. Natural visual quality remains
rejected; reported short-run FPS does not prove completed-product performance.

## Static HDR water (2026-10-16)

[WATER_HDR.md](WATER_HDR.md) records the integrated `qa-water-hdr-7` pass,
independent source-triangle optics/shading checks, static camera motion and
retained earlier failures. The verifier covers face interiors and excludes
source normal-discontinuity footprints. This establishes neither animated water
nor completed cinematic acceptance. Host JAR: `22EF8935ECD312580BC6A8D7CF9042FD0528A00CBDB1788FC9603CC003BE64C5`.

## Separated material color (2026-10-15)

[MATERIAL_COLOR.md](MATERIAL_COLOR.md) records the new 32-byte source vertex
contract, actual encoder/cross-page GPU tests, live-atlas AO/color comparisons,
all 32 lighting jitter phases and natural scene capture. Latest tested JAR:
`F353604B93C5D7C4BD0B0200F657A6E8F710AE1F235F59E6A662A28C80BD84EE`.
An earlier diagnostic-sentinel failure is retained; visual quality is still
unfinished. Historical records below retain their original artifact hashes.
Clean local JAR/native rebuild hashes match the tested artifacts; see
`evidence/material-color-build-repeat.json`. This is not independent-machine
reproducibility or a release acceptance claim.

## Natural world and paged attributes (2026-10-15)

[NATURAL_WORLD.md](NATURAL_WORLD.md) records the normal forest/river scene, its
failed visual-quality assessment, unthrottled wall cadence and the earlier AFK
limit. [PAGED_ATTRIBUTES.md](PAGED_ATTRIBUTES.md) describes the capacity failure
at eight chunks and the address-directory/page replacement, including actual
two-page GPU decoding and full integrated regression evidence. Latest tested
JAR: `FDF0B46A639C73BFC3F95979D51E221DB8C6D395FF43F0A08100F2653C44EDB3`.
The preceding atmosphere evidence remains historical at its recorded hashes.
The same JAR also passes ordinary live-atlas/reload regression and clean local
JAR/native hash reproduction (`evidence/attribute-paging-build-repeat.json`).

## Atmospheric lighting evidence (2026-10-15)

See [ATMOSPHERE.md](ATMOSPHERE.md) for the latest integrated run, all 32 jitter
phases, independent quadrature, failures and scope limits. Its evidence is
archived under `evidence/qa-atmosphere-*`. Shader-pack hashes are recorded
separately from the JAR because example packs are not embedded in it.
The no-atmosphere pack regression initially failed due to a missing conditional
include (`qa-atmosphere-baseline-6`). The generator was corrected;
`qa-atmosphere-baseline-7` passes actual world DLSS and lifecycle with the
atmosphere disabled. Clean JAR/native rebuild hashes match the tested artifacts.
`qa-atmosphere-materials-8` passes the live-atlas material comparison before/after
reload on the same JAR. See `evidence/atmosphere-build-repeat.json` for exact
hashes and archived clean-build logs. No independent-machine reproducibility or
representative-world 100 FPS acceptance is claimed.

Latest update: 2026-10-15 (Europe/Moscow). Earlier sections below retain their
historical scope; the following evidence supersedes earlier statements that
game-world DLSS and actual size replacement have not been exercised.

## Latest: world DLSS and opaque ray reflections

The latest ray-coordinate milestone is described in `RAY_COORDINATES.md`:
`evidence/ray-origin-world-1` passes actual +/-16M world reflection/removal,
fractional camera position, NGX at 1440p, near-world return and retirement.
The independent GPU precision/rebase probe proves unchanged BLAS, TLAS-only
rebasing and view reuse on small motion. That milestone's tested JAR SHA256 is
`C034583C273047CC3F32ED3A288CD408E7DAA4CAD39E22C5DF8FDD6044ED3D80`.
Ordinary direct-light and live-atlas/reload regression runs pass on the same JAR.
Clean offline JAR and native adapter rebuilds match their tested hashes; see
`evidence/ray-origin-build-repeat.json` and the archived build logs.

Newer alpha-tested terrain traversal evidence is in `ALPHA_TRAVERSAL.md` and
`evidence/alpha-reflections-dlss-6`. That real-game combined run passed 161,940
selected CPU triangle/alpha samples, offscreen reflection, removal, NGX Quality,
camera/jitter, resize and retirement. Its JAR SHA256 is
`A75B2B994878CD7C047343D18F00A0C631DA3DAB113D0AF1CA6EF5F00583EE96`.
The alpha scene is stationary, explicit LOD 0 and binary oak alpha; it does not
prove animated/mip-filtered foliage or the separate shadow-any-hit oracle.
The same artifact also passed the ordinary reflection and direct-light regressions
without alpha diagnostic overrides, archived in `evidence/alpha-reflection-regression-1`
and `evidence/alpha-direct-regression-1`.
A clean offline rebuild and native adapter rebuild match their tested hashes;
see `evidence/alpha-build-repeat.json`, with per-run pack manifests/fingerprints.

See `REFLECTIONS.md` for exact hashes, independent oracle coverage, failed run,
corrected target lifetime, reproducible build scope and reproduction commands.

| Check | Result | Scope |
| --- | --- | --- |
| Offscreen reflection + world NGX Quality | PASS | `evidence/reflections-dlss-2`; entire emitter outside frustum, 11,813 analytic ray samples, HDR composition, emitter removal |
| World Quality resize | PASS | 1707×960→2560×1440 followed by 1280×720→1920×1080; finite/nonuniform RGB, not fidelity |
| Ordinary direct-light regression | PASS | `evidence/reflections-regression-1`, same JAR; unchanged quartz CPU BRDF/shadow oracle |
| Sky target lifetime | Corrected / PASS in the above runs | Earlier combined run failed resize; retained in `evidence/reflections-dlss-failure-1` |
| Repeat clean offline JAR build | PASS | Identical SHA256 `FC104180D4E79DC85C2BE515227F679AE860EAE407ED158C42DDEB8EB2515848`; current local toolchain |
| Other mod binaries | Unchanged | Fabric API, Sodium and Voicechat hashes checked by both final real-game runners |
| Cinematic quality, rough/transparent/dynamic RT, reflection history, representative 100 FPS | NOT ACHIEVED | Engineering reflection fixture does not cover these requirements |

## Earlier evidence

| Check | Result | Scope |
| --- | --- | --- |
| Installed Sulkan artifact identity | PASS | SHA-1 matches official Modrinth 0.5.1 artifact |
| Website shader source retrieval | PASS | Official Sulkan Realistic 0.5.1 archive downloaded |
| Restored Java source compilation | PASS | Java 25 javac, installed Minecraft/Sodium/Fabric dependencies, annotation processing disabled |
| Local dependency-pinned baseline JAR build | PASS | 97 runtime libraries plus Fabric API and Sodium verified by SHA-256 |
| Restored mod loading and bootstrap | PASS | Actual disposable Minecraft launch, 53 loaded mods, Sulkan 0.5.1-cinematic.0 initialized and enabled built-in pack |
| Standard Gradle build | PASS | Verified Gradle 9.4.1 distribution, Loom 1.16.1, Java 25; initial build and two later clean builds completed. Clean independent checkout remains pending |
| Local archive reproducibility | PASS | Two clean builds produced identical SHA-256 for binary and source JARs; shader comments retained, archive timestamps normalized |
| Hardware Vulkan capability probe | PASS | Real RTX 5070, Vulkan 1.4.329 |
| Hardware buffer device address | SUPPORTED | Physical-device feature query returned true |
| Hardware acceleration structures | SUPPORTED | Physical-device feature query returned true |
| Hardware ray queries | SUPPORTED | Physical-device feature query returned true |
| Hardware ray tracing pipeline | SUPPORTED | Physical-device feature query returned true |
| Optional ray query features enabled in Minecraft logical device | PASS | Actual second game launch reports Sulkan feature set enabled; device contains ray query, acceleration structure, and deferred host operations extensions |
| BLAS/TLAS construction and GPU ray intersections | PASS | Real Minecraft Vulkan device; 12 rays, two translated instances, independent CPU reference, hit distances, primitive IDs, masks, clipping, back-face hit and misses verified |
| GPU ray-input serialization | PASS | Compute shader echoes decoded ray parameters; compared against the original CPU records |
| Diagnostic shutdown | PASS | Conformance and both disposable world runs stopped normally with exit code 0; first world shutdown was unusually slow |
| World rendering / reload / resize | PASS | Two disposable Minecraft runs; actual mesh capture, block removal, shader reload, 1024×576 render target resize, screenshots and normal shutdown |
| Indexed world geometry intersections | PASS | Four fixture rays before and after removing a stone block; expected distances independently fixed by fixture geometry |
| Incremental scene construction | PASS | Third disposable world: one-mesh build budget, five initial batches, three update batches, unchanged scene does not rebuild; opaque fixture rays still agree |
| Deferred scene resource retirement | PASS | Fourth run reports zero live owned ray buffers after retirement, all required QA markers present, normal exit code 0 |
| Ray graph schema and binding contract | PASS | Six CPU checks: propagation through pass selection, AS binding index and extension ordering, legacy compatibility, rejection of string/fragment rayScene declarations |
| Ray visibility in normal frames | PASS WITH LIMITED SCOPE | Seventh disposable run dispatched 494 ray passes before baseline screenshot; opaque sun-visibility mask inspected, all QA markers present, normal exit code 0. No cinematic lighting or performance claim |
| Restored Sulkan runtime equivalence | PARTIAL | Loading and world rendering verified; complete visual equivalence to official artifact remains unverified |
| Deferred HDR direct ray lighting | PASS WITH LIMITED SCOPE | Tenth disposable run: 657 quartz fixture pixels matched independent CPU slab shadows and BRDF math; 22 shadowed / 635 sunlit; maximum absolute error 0.000489; HDR peak 6.1875 and emissive coverage verified |
| Diagnostic matrix snapshot decoding | PASS | Heap, direct, other-endian and read-only buffers checked in six cases; array-based JOML decoding avoids unsupported heap FloatBuffer native access |
| Ray-hit surface attribute contract | PASS WITH LIMITED SCOPE | CPU cases for 20/24-byte vertices; actual GPU ray queries on both indexed quad triangles, 12 rays including a back-face hit and misses; UV/bias, tint, lights, wind, flat material flags, position and normal checked |
| Attribute arena allocation | PASS | 10,000 deterministic CPU allocate/release operations: alignment, stale releases, accounting, overlap, exhaustion and coalescing checked |
| Device-local shared attribute/geometry input | PASS WITH LIMITED SCOPE | Pooled GPU fixture uses a nonzero header and same device-local buffer for BLAS input and shader attributes; world QA builds/rebuilds actual fixture geometry through the arena, then retires it with zero owned buffers |
| Ray reflections / indirect illumination | NOT IMPLEMENTED | Direct sunlight is implemented in the experimental pack; traced reflection/material transport and indirect lighting remain pending |
| DLSS | NOT IMPLEMENTED | SDK integration and motion buffers pending |

The device negotiation check is gated by `-Dsulkan.rayTracing=true` and uses
Minecraft's own optional feature-set negotiation. It does not replace the device
or maintain a second GPU device. The normal game and all external mod JARs remain
unchanged. This validates enablement only, not acceleration structure building,
ray intersection correctness, performance, or lighting.

Both disposable runs used an offline QA identity. Account/Realms authorization
errors in those runs are expected and do not establish a rendering failure.
The two initial startup runs were explicitly terminated after observation.
The later ray-query conformance runs stopped through Minecraft's shutdown path.
World lifecycle tests are described below.

## Disposable world verification

Runs `world-qa-1` and `world-qa-2` used fresh worlds under the workspace,
protected by the QA directory marker. The installed instance was unchanged.
The second run captured 446 sections / 83,922 triangles / 7,049,448 CPU bytes,
including cutout and translucent meshes. Tests compare actual Sodium vertex
encodings at both 20-byte and 24-byte strides, then overwrite the original
native buffer to check independent ownership.

The fixture's first four GPU rays returned hit distances [3, 2, miss, 14].
After removing its stone block they returned [4, miss, miss, 14]. Returned
primitive and instance IDs were checked against captured indexed meshes.
These tests cover opaque terrain, not alpha evaluation, wind deformation,
entities, ray lighting or performance. Evidence screenshots show baseline
lighting, not a finished cinematic renderer.

The first world's shutdown took approximately four minutes while the server
saved chunks. A thread dump was retained; it does not establish a Sulkan cause.
The second run shut down promptly. Resize produced a recoverable Vulkan
swapchain-out-of-date warning; rendering resumed and the resized target passed.

## Gradle environment recovery

The initial wrapper TLS failure and daemon failures remain recorded. The
verified local Gradle distribution completed the standard build after setting
`jdk.net.unixdomain.tmpdir` to the workspace's existing `work/tmp` directory.
Setting only `java.io.tmpdir` did not resolve the Java Unix-domain socket path.
Gradle reported deprecated features; unit test tasks had NO-SOURCE, so build
success is not evidence of unit tests passing.

## Incremental scene scope

`TerrainRayScene` records BLAS builds only for accepted changed sections and
reuses unchanged BLAS objects. It builds a TLAS for each accepted update batch.
The view exposes instance-to-mesh metadata. Empty scenes have handle zero;
callers must skip tracing them. GPU work uses Minecraft's existing graphics
encoder. Old TLAS objects are retired before their replaced BLAS objects;
build upload/scratch buffers are released through that encoder's deferred
destruction queue. The class neither submits nor calls device-idle waits.

Run `world-qa-3` verified opaque fixture queries before/after block removal,
budget draining, an unchanged-view check, clearing the scene, and recording
deferred retirement. It exited normally with code 0. The later diagnostic
checks zero remaining `NativeBuffer` allocations after normal rendered frames.
Diagnostic readback still uses an explicit device-idle wait, separately from
the scene implementation. Normal-frame integration is now exercised by the
visibility diagnostic below. Larger scene allocation pooling, alpha-aware
traversal, dynamic meshes and performance
remain unverified or unimplemented. A mesh-count budget is not a measured
millisecond budget and does not prove the 10 ms frame target.

## Ray visibility image integration

Passes explicitly declare `rayScene: true`; legacy packs remain independent of
ray features. The implementation verifies actual logical-device negotiation,
records scene updates before dispatch, seeds terrain ownership on reload and
retires resources through the existing encoder. No device-idle wait is added
to normal ray frames. The diagnostic pack reconstructs surfaces from Vulkan
reversed depth and traces rays toward the sun against opaque terrain only.

The first visual attempt failed because the test pack was placed in
`shaderpacks` while Sulkan reads `shaders`. The next failed during empty-scene
clear because ray outputs lacked COPY_DST usage. Both failures, including exit
code 0 alongside explicit FAIL markers, are preserved in `world-qa-5` and
`world-qa-6` evidence. Corrected allocation permissions account for physical
resource aliasing rather than individual logical resource names.

Run `world-qa-7` executed the ray pass in normal frames, produced an inspected
white/black visibility mask and blue sky, switched back to the built-in pack,
verified zero remaining owned ray buffers, resized and exited normally. The
latest compiled binary SHA-256 matches the tested artifact exactly:
`d1e13dc0f0c045791d26c972d3759990e4e118ac527e3d15a07adc35f688e2d7`.
Shutdown again took about four minutes while saving world chunks. Its thread
dump shows the server waiting on a region-file write; the root cause remains
unestablished. This run proves dispatch, a displayed diagnostic and retirement,
not a quantitative pixel reference, complete material evaluation, ray-lit HDR,
temporal stability, distant-coordinate precision, DLSS or performance.

## Initial deferred HDR lighting

The experimental pack writes tinted linear albedo/skylight, geometric world
normals/roughness and emission/metalness to RGBA16_FLOAT attachments, alongside
exact-depth surface identification. A compute pass evaluates diffuse/GGX direct
sunlight gated by an opaque hardware ray query. HDR stays intact until tone-map
and sRGB presentation. Class presets, baked vertex shading, approximate sky/block
fill, opaque-only casters and simple translucent rendering remain limitations.

Run `world-qa-8` failed because the inherited translucent shader declared raster
shadow uniforms while that shadow layout was disabled. It now has its own
minimal fallback shader. Run `world-qa-9` reached GPU readback but crashed in
the CPU diagnostic: JOML's native-order FloatBuffer path assumes direct memory
without optional debug checks. The crash and bytecode evidence were retained.
Matrix snapshots now use scalar reads and an ordinary float array; the
six-case regression passes.

Run `world-qa-10` passed all required markers, normal exit code 0 and unchanged
other-mod hashes. GPU attachments were copied asynchronously. Independent CPU
slab intersections against the fixture stone and double-precision BRDF math
checked 657 quartz pixels, including 22 shadowed and 635 sunlit. Maximum error
was 0.0004881832. All HDR channels were finite/nonnegative; peak RGB was 6.1875
and 615 opaque pixels carried emission above display range. Reload retired all
owned ray buffers; resize and shutdown passed. Screenshot and numeric/process
evidence are retained in `evidence/world-qa-10`.

This fixture does not prove calibrated PBR, alpha-aware casters, entities/wind,
reflection, indirect illumination, temporal denoising, transparent optics,
complete world integration, DLSS or the requested 1440p / 100 FPS performance.

## Ray-hit attributes

The initial shared CPU/GLSL contract stores object positions and packed Sodium
attributes in 32-byte records with original indexed triangle order. CPU tests
check UV shrink/bias, tint, lights, flat material/terrain tags and independently
varying wind weights for both vertex formats, both triangles, source-buffer
ownership and invalid inputs.

The actual Minecraft-device GPU test built an indexed quad BLAS and TLAS. For
both layouts it traced six rays (12 total), including both triangles, an
opposite-facing ray and a miss. The shader used committed ray-query barycentrics
to decode that same payload; UV, colors, positions, normals, lights, material and
wind outputs agreed with analytic positions/barycentrics and the checked CPU
decoder. NaN sentinels detect missing writes. The client stopped with exit code
0 and no failure marker; owned buffer count was zero. The original 12-ray,
two-instance mask/clipping diagnostic also passed with normal exit code 0 after
the diagnostic pipeline extension.

Evidence: `ray-surface-contract.txt`, `ray-surface-gpu-1.txt` and
`ray-regression-1.txt`, plus their process records. The tested binary SHA-256 is
`d056f2c7add7adc412a6811dba2fc664ae1e00badfcacfebf12c355c3a84fd1a`.
This fixture does not prove world-atlas sampling, alpha visibility, wind geometry,
reflections, indirect illumination or acceptable full-scene allocation costs.

## Shared device-local attribute arena

`RayAttributeArena` reserves at most 64 MiB, further bounded by the device's
storage-buffer range limit. Aligned segments hold immutable mesh payloads until
their GPU readers complete. Their header word offsets fit the 24-bit instance
custom-index field, so a ray can locate its payload without a separate sparse
lookup table. First-fit allocation coalesces released neighbours and detects
stale serials. The CPU stress test performs 10,000 deterministic operations.

Uploads use staging buffers and recorded graphics-queue copies into device-local
storage. The same persistent payload supplies float XYZ at vertex stride 32 and
uint indices for BLAS builds; separate geometry input uploads are eliminated.
Build input barriers use SHADER_READ access, as specified in the
[Khronos build command reference](https://github.khronos.org/Vulkan-Site/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html).
Production scene lifetime uses Minecraft's deferred destruction queue; no
device-idle wait is added by the arena or scene builder.

The pooled surface diagnostic passed both vertex formats and 12 rays while
reading a nonzero header in that buffer. World run `world-qa-11` built and rebuilt
actual fixture geometry through the optional arena and checked instance/header
identity. Reserved capacity was 67,108,864 bytes; reported used bytes were
208,672 before removal and 345,520 during deferred retirement after removal.
These include ranges awaiting completion, not only the current view. The
existing 657-pixel HDR check passed unchanged, all ray buffers retired, resize
and normal shutdown passed, and other mod hashes remained unchanged.

This is optional scene infrastructure and diagnostics. Normal pack compute
passes do not yet bind this arena for world material sampling. Animated block
atlas access, alpha candidates, reflected/indirect radiance, larger-scene budgets
and performance remain required work. AS storage/scratch allocation pooling and
staging-buffer reuse are also pending; this pools attribute/geometry input only.

## Hardware ray query conformance

The corrected run completed on 2026-10-14 at 18:21:55 Europe/Moscow. It built
one non-indexed triangle BLAS and a TLAS with two differently translated and
masked instances. The compute shader traversed that TLAS on Minecraft's actual
device and wrote results to a host-coherent buffer. Each result was compared
with an independent analytic ray/plane intersection and triangle bounds.
The output started as NaN sentinels so missing GPU writes cannot pass.
The shader also echoed ray parameters to verify the GPU input layout.

Initial runs failed. Diagnostics showed both builds used geometryCount=0:
LWJGL's pGeometries setter does not set this shared count field. The corrected
builder sets geometryCount explicitly. The original failures and corrected
readback are retained in evidence. A game exit code of 0 alone is not a pass;
the explicit conformance marker and absence of its failure marker are required.

The diagnostic deliberately waits for device idle before CPU readback and
resource destruction. This global wait is confined to the opt-in diagnostic;
production rendering still needs asynchronous builds and deferred retirement.
Dedicated allocations are an initial ownership implementation; production
chunk-scale allocation limits and budgets have not been validated.

## Actual DLSS GPU frame evaluation (2026-10-15)

The native session adapter creates a DLSS feature and records its evaluations
on Minecraft's existing encoder. The opt-in world-free diagnostic submitted
four real evaluations and copied GPU output into a host-coherent buffer. All
3,686,400 RGB pixels per output were checked; output started as NaN sentinels.
Two frames use (0.18,0.36,0.72), then two use (2.0,0.12,0.04), with resets at
the color changes and history retained on repeats. Quality input is 1707x960,
output 2560x1440. Maximum absolute channel errors are 0.0017383098602294922
and 0.00390625, below max(0.005, 0.5% of the expected channel). This checks
basic HDR reconstruction, including values above 1, rather than only SDK status.

Run 6 passed with explicit capability/frame markers, exit 0, matching hashes
and unchanged non-Sulkan mod copies. Evidence: evidence/dlss-frames-6/result.json,
dlss-frames.json, complete logs and four output previews. PNG previews clamp
linear values for display; numerical checks use the unclamped float16 readback.
Alpha is not reconstructed by the selected feature flags and is not checked.

Tested JAR SHA256:
4928EA226798A1177631CD6656AD69566EB6E8816AB067190728D567F749098A.
Native session DLL SHA256:
F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257.
External release runtime SHA256:
3975567B8943C53ACCE397F2B72380092F84F162D00B0D2C7D08A1025C563983.

Retirement through queueForDestroy returned success for feature release,
parameter destruction, capability destruction and SDK shutdown; native registry
count was 0. These comparisons deliberately wait for device idle between
frames. Normal adapter methods do not wait or submit another queue. In-flight
retirement stress and overlapping-context ownership are not yet tested.
Nor are nonuniform detail, jitter, object motion, dynamic exposure, gameplay
reconstruction or FPS. No game-world frame has been routed through DLSS.

Failures are retained under evidence/dlss-frames-1 through -3. Run 1 launched
an old artifact after a compiler error; absent frame markers correctly failed
the runner. The runner now checks diagnostic class presence before launch.
Runs 2/3 with the SDK development runtime failed strict pixel comparison because
its visible NVIDIA development watermark changes output. The full-frame
watermark capture is retained in run 3. Release-runtime runs compare the whole
image; no watermark-region exclusion was added. Earlier run 4 used lower-range
colors and run 5 added red=2; its runner scope string was subsequently corrected
in run 6 to distinguish capability-only and GPU-frame evaluation.

Two same-checkout native session rebuilds are byte-identical:
evidence/dlss-session-reproducibility.json. Independent clean-checkout builds,
Vulkan validation layers and failure injection remain unverified.

## NGX initialization and DLSS capability (2026-10-15)

An original MIT-licensed JNI probe was compiled against external NVIDIA SDK
commit 374959484e79a640feaba44c93ac8cfb0a03f5b5 (API 21) and the development
runtime 310.9.1.0. SDK headers/libraries/runtime remain outside the repository;
the JAR does not bundle them. Source: native/dlss/ngx_probe.cpp.

The first real Minecraft launch initialized NGX successfully but DLSS was
unavailable. The SDK log specifically reported missing vkCreateCuModuleNVX /
vkCreateCuFunctionNVX entry points and requested VK_NVX_binary_import. The
returned requirements also included VK_NVX_image_view_handle,
VK_KHR_buffer_device_address and VK_KHR_push_descriptor. This failure is kept
in evidence/dlss-capability-1; process exit 0 did not pass the verifier.

The opt-in feature set requests those extensions and bufferDeviceAddress using
Minecraft's existing Vulkan-1.2 feature structure. Later SDK launches confirmed
the exact API/extension contract, successful initialization, availability=1,
needsUpdatedDriver=0, optimal Quality input 1707x960 for 2560x1440 output,
parameter destruction and shutdown. The final rebuilt native binary passed
with both ray-query and DLSS feature sets enabled. Evidence:
evidence/dlss-capability-5/result.json, dlss-probe.json and complete logs.

Final native DLL SHA256:
9E7D2B9E4E6B84066C5790A748906AD892598AE8541A6010D63C1436AC8A0D3B.
Tested JAR SHA256:
B1C665FACB968D4B727FE18300C0A4A4CD9C4E968BA16221DE31F8DDFF18D1AF.
External development runtime SHA256:
378262F4BA429E6199CE25D6F9275734F354C3B017F3F096D8CEA1AF468312FF.

These checks prove initialization/capability queries, not feature creation,
evaluation of a frame, gameplay motion, visual quality or the 100 FPS target.
Native exception cleanup paths are implemented but have not been fault-injected.
SDK destruction status does not prove absence of every internal driver allocation.

### Existing world renderer with the combined device features

evidence/world-dlss-features-1/result.json confirms a real world run with both
optional device feature sets enabled. The existing quartz lighting/material
checks pass before and after full atlas reload: 657 hit coordinates, 654
stable-texel colors and 3 reported texel-boundary cases. Block removal,
switching to the builtin pack, resize, native buffer count 0 and normal exit
pass. All three other mod hashes remain unchanged. This world run does not
load NGX or evaluate DLSS alongside ray dispatches.

### Native acquisition and repeatability

The MSVC compiler and NVIDIA runtime signatures are valid. The pinned external
dependencies verify through Get-NativeDependencies.ps1; the JNI headers come
from OpenJDK 25 because Prism's runtime lacks them. Microsoft packages were
checked against their individual payload hashes; Windows SDK NuGet files were
also checked against the catalog's published SHA512 values.

Initial acquisition failures were not ignored: the Visual Studio channel's
manifest hash did not match the delivered manifest bytes, and the NuGet
flat-container .sha512 endpoint was absent. The manifest is not considered
verified. The bootstrap uses explicitly pinned individual package hashes and
compiler signature verification; NuGet catalog metadata supplied the hashes.
Missing compiler language resources, JNI headers, common CRT oldnames.lib and
user32 import library were resolved with the external dependency set.

Two native rebuilds produce identical bytes with /Brepro and no incremental
linking. Evidence: evidence/dlss-native-reproducibility.json. This does not
establish clean-checkout or cross-directory repeatability. The flag is also
used in Microsoft's BuildXL linker configuration:
https://github.com/microsoft/BuildXL/blob/main/Public/Sdk/Experimental/Msvc/Native/Tools/Link/Link.dsc

## World ray-hit materials (2026-10-14)

Regular graph compute passes now opt into `rayAttributes`, which binds the
persistent arena after the acceleration structure. `$terrainAtlas` resolves the
live Minecraft block atlas and borrows the current terrain sampler. The engine
supplies Sodium's device-dependent subtexel shrink scale. No non-Sulkan mod was
modified. These interfaces do not implement reflection or indirect radiance.

Run 8 passed on the disposable world, with explicit markers and process exit 0.
Evidence: evidence/ray-hit-materials-8/result.json and deferred-lighting.json.
Tested JAR SHA256:
1222502FCF3516E41C03D8693ED02DAE5717E74547C42CE5F00C079256590C53.
It checked 657 quartz hits, 654 stable-texel colors, and explicitly classified
3 nearest-texel boundary samples. Maximum UV error was 0.00778961181640625 texel,
maximum tint error 0.0000814199447631836, maximum color error 0.00048828125,
and distance error 0.0006103515625 block. All 657 hits passed UV/tint/distance
checks. Color comparison uses matching LOD 0 and matching selected texels;
up to 10% boundary cases are permitted and counted, with at least 40 stable
color samples required. Limits are 1/64 texel UV, less than half an 8-bit source
tint step (1/512), 0.003 linear color, and 0.01 block distance in this fixture.
This is not a claim of exact raster/ray interpolation or all-world correctness.

The existing independent CPU shadow/BRDF reference still passed 657 samples.
Block removal, resource reload to the builtin pack, resize, and complete native
buffer retirement also passed. Runtime mod hashes remained unchanged.

Earlier failed runs are retained under evidence/ray-hit-materials-1 through -7.
Run 1 exposed the graph pruning an unconsumed diagnostic pass; the diagnostic
outputs were then made dependencies of the present graph. Subsequent comparisons
established that mip/filter policy and nearest-texel discontinuities must be
distinguished from incorrect UV/material decoding. A generic atlas sampler was
replaced with the actual terrain sampler. Added UV/tint readbacks identified
subpixel raster versus ray interpolation differences; the final test reports
those errors and separately counts texel boundaries instead of relaxing color
agreement globally. Initial normalized UV/tint tolerances were replaced with
explicit texel/source-quantization tolerances as recorded above.

CPU schema/source checks pass 10 assertions including binding order, strict
boolean validation, legacy behavior and preservation during pass selection.
Evidence: evidence/ray-graph-attributes-contract.txt. Compilation remains
separate evidence; Gradle's test task has no unit-test sources.

### Same-pack atlas reload follow-up

Run 9 repeats the material and lighting readback after a full resource reload
while RayLighting remains selected. Both 657-hit comparisons passed with the
same reported errors and boundary counts. Then block removal, switching to the
builtin pack, resize, native-buffer count 0 and normal exit passed. Runtime mod
hashes remained unchanged. The existing recoverable swapchain-out-of-date
warning occurred during resize; the subsequent resize/world markers and exit
were successful, and its full log is retained.

Evidence: evidence/ray-hit-materials-9/result.json, stdout.txt,
deferred-lighting-before-atlas-reload.json and deferred-lighting.json.
Tested JAR SHA256:
34CF7E39395ADBD7539D74D7CE281383DCCD44EFA3D4021A07543A3C54752CDA.
This extends atlas/descriptor lifetime evidence; it does not test changes to
sampler settings, animated texel sequences, reflection filtering or performance.

## Shader menu selection regression (2026-10-14)

The recovered menu compared MouseButtonEvent.button() with 0. Minecraft 26.3
uses InputConstants.MOUSE_BUTTON_LEFT = 1. The handler now uses that named
constant. In a marked disposable Vulkan instance, the opt-in menu diagnostic
sent mouse events through the actual ShadersScreen: right click left the builtin
pack selected, then left click applied RayLighting and completed resource reload.
The explicit SULKAN_SHADER_SELECTION_PASS marker was present, the failure marker
absent, and the process exited 0. Evidence: evidence/shader-menu-1/result.json
and stdout.txt. Tested JAR SHA256:
8D4BE1CD97BBF4A647E0AE4A7ED9F0583954A7E5587C91814F39FFED5C6EA7E9.
This run checks menu selection and pack reload, not world rendering or FPS.

## Camera motion and frame cadence (2026-10-15)

`evidence/camera-motion-4/result.json` proves both signs of camera translation,
world lifecycle, reload, resize and deferred buffer retirement on the actual
Vulkan renderer. JAR SHA256:
749174925F453990D5551CFE51F693BF24D30E2DD329D12C190673A5796D1A5E.
The three other mod hashes are unchanged. Positive translation was 0.0625
blocks, 346 samples, maximum error 0.000251793 pixels. Negative translation was
11.8125 blocks, 618 samples, maximum error 0.0628605 pixels; the RG16_FLOAT
comparison allows 0.005 pixels plus 0.2% of expected motion. A later runner
revision uses a continuous camera target to avoid this large reversal jump.

Earlier runs 1–3 failed because the independent quartz-plane oracle included
pixels occluded by the raised stone. Run 3 reconstructed the offending surface
at (4.94608,97.00012,4.98930), on that stone. The correction performs independent
ray-box occlusion against the known stone before testing quartz; it does not
increase the error tolerance. Run 2's narrower quartz patch was insufficient
because perspective projects the stone onto a different point of the plane.

Diagnostic history readback now selects the last rendered image after parity
advances, rather than the next frame's image. Older stationary history tests
did not demonstrate correctness of that moving-frame association. Ray-hit
material history must be rerun with the corrected selection.

The run's steady fixed-camera window recorded 238 frame intervals at 854x480,
mean 59.7857 FPS, median 16.7754 ms, p95 19.0567 ms, maximum 32.2411 ms. The
60 FPS cap is active. This wall-clock cadence includes CPU, GPU and throttling
but excludes the later diagnostic readback phase. It is not a 1440p benchmark,
does not reproduce every reported stall and does not establish 100 FPS.

CPU math evidence is retained as `cpu-math.txt`: 32 jitter phases, three extents,
27 camera translation/rotation cases. Game jitter, moving surfaces, disocclusion
quality, complete DLSS gameplay and cinematic visual quality remain untested.

Repeat `evidence/camera-motion-5` uses continuous positive/negative camera
steps of 0.0625 blocks. It passes 618/751 nonzero quartz samples, with maximum
errors 0.000267731/0.000510503 pixels. Both world lifecycle and unchanged-mod
checks pass. The steady window records 59.6441 FPS (237 intervals); the motion
and readback window records 59.4837 FPS (489 intervals), p95 18.3099 ms,
maximum 44.0885 ms. These capped 854x480 windows do not reproduce sustained
10–15 FPS; startup, focus changes and other scenes are outside this measurement.
JAR SHA256: 73F665C0FF0662C9A7E3BAFD04B3F8EF08F84627332CF7586DE763F43351EC1F.

Stationary ray-hit material history was rerun with corrected readback in
`evidence/hit-history-1`, using the same JAR. It passes the pre/post atlas reload
comparisons (657 quartz samples, 654 color samples, maximum albedo error
0.00048828125), lifecycle, resize and zero-live-buffer retirement. A transient
`swapchain out of date` warning occurred during resize; the subsequent resize
and world completion markers prove recovery. This run does not prove moving
history association or absence of every resize stall.

Moving-frame association is exercised in `evidence/camera-history-6`: the
diagnostic motion and validity resources use two history images. Both camera
directions pass independent projection with the same JAR as run 5, 469/776
nonzero samples and maximum errors 0.000266287/0.000503231 pixels. The full
lifecycle, resize and retirement gates also pass. `tested-sulkan.json` and
post-run `pack-sha256.json` identify the exact generated diagnostic pack.
The moving/readback window records 1,839 intervals, mean 59.6512 FPS, p95
18.2172 ms, maximum 43.7362 ms at the same 60 FPS cap and 854x480 extent.
This verifies readback from the last rendered history image while camera data
changes, not accumulated temporal filtering or DLSS reconstruction quality.

## World projection jitter (2026-10-15)

`evidence/world-jitter-2/result.json` passes the guarded world lifecycle using
opt-in actual world projection jitter, motion-history images, and negotiated
DLSS device features. JAR SHA256:
46D73BA7299045F3757A4B08228F5AA6B8D034CFB5F528A2E7BFF87B06345DC3.
The three other mod binaries are unchanged. The stationary-camera check covers
663 quartz samples, with zero samples over 0.01 motion pixels and peak residual
0.0000394571 pixels despite different current/previous jitter. Translation in
both directions also passes. The independent oracle projects known quartz and
rejects known stone occlusion; sample jitter is removed before comparing motion.

The CPU probe in `cpu-math.txt` passes 864 jittered camera translation/rotation
cases at three extents, plus the prior 27 unjittered cases and 32-phase sequence.
The earlier world run is retained in `evidence/world-jitter-1`; it lacked the
stationary-camera gate added for run 2. Neither run evaluates DLSS on game
imagery, measures 1440p production performance, validates animated entity motion,
or demonstrates temporal reconstruction quality. Raw jitter is visible in this
test because no temporal reconstruction consumes it yet; the flag defaults off.

## Renderer-owned DLSS generation (2026-10-15)

`evidence/dlss-reconstruction-2/result.json` passes the real GPU frame diagnostic
through `DlssReconstruction`. JAR SHA256:
EA055A406F12AE96584971B8548E4F59FEE6C0CA9D8B343117B7CF064C8D475D.
The native adapter and external release runtime retain their hashes from
`evidence/dlss-frames-6`; the three other mod binaries are unchanged.

The renderer generation owns the 2560x1440 HDR output and obtains the 1707x960
Quality input extent from NGX. Feature creation now shares the first evaluation's
caller command buffer. All four constant-HDR frames pass the same full-RGB-pixel
comparison and NaN output sentinel as before, with maximum RGB errors
0.00173831 and 0.00390625. The wrapper records memory dependencies before/after
NGX and calls no queue submit, readback or global wait; diagnostic-only waits
remain in `DlssFrameConformance`.

The diagnostic deliberately supplies RG16_FLOAT as depth and proves rejection
before the evaluation counter advances. It also proves output access and a
duplicate retirement request are rejected after retirement starts. Native
feature/parameter/capability/shutdown return success, with zero live contexts.
Output image/view destruction follows the deferred native retirement callback.
Actual size replacement, overlapping generations, in-flight retirement stress,
nonuniform imagery, gameplay integration and performance remain unverified.
The first wrapper run is retained in `evidence/dlss-reconstruction-1`; it did
not include the added rejection checks.

## Post-SR exposure checkpoint (2026-10-15)

`qa-auto-exposure-3` and `qa-auto-exposure-4` pass histogram metering, initial
blank/reset policy, pointwise EV recurrence and actual output display mapping
on host 0EE1EE4AA43FFC18C8D105EBEA66A243277968323E9E98AD56EAFA5B1AEF09F5.
They also pass the integrated leaf/transport/NGX/lifecycle checks. The second
uses explicit separate ordinary/exposure display shaders; the failed disabled
variant's uniform-discovery error is preserved. `qa-auto-exposure-disabled-2`
and `qa-auto-exposure-feature-off-1` pass previous reconstructed and legacy
paths. Other mod hashes remain unchanged.

`qa-auto-exposure-natural-2` passes source/display checks at day and sunset,
but the imagery still fails the cinematic target: dark canopy and severely
overbright fallback water remain. The initial log-average preset was rejected
for washed-out daylight despite passing its arithmetic. See AUTO_EXPOSURE.md
for current weights, bin-boundary checks, scope, raw metrics and remaining
adaptation-stability tests. Earlier checkpoints retain their historical scope.

## Static leaf receiver checkpoint (2026-10-15)

`qa-leaf-indirect-7` and its repeat `qa-leaf-indirect-8` pass. The latter host is
E61768F7D67B36207854A507C6FEDD41B63AD7F2043938D3F2063FB73231211E.
Front/back raw transport is checked against source triangles/PNGs; both histories
and six spatial passes have arithmetic checks. Leaf HDR composition and static
leaf motion have separate references, including primary source/raster positions.
All 32 lighting jitter phases and existing world lifecycle gates pass. See
LEAF_INDIRECT.md for exact scope, preserved failures and the diagnostic texture
lifetime correction. Wind is explicitly disabled; complete canopy transport,
high-sample convergence, gameplay, performance and cinematic acceptance remain
open. The following checkpoints describe earlier hosts and feature scopes.

## Spatial diffuse-light checkpoint (2026-10-15)

`qa-indirect-spatial-1` and `qa-indirect-spatial-natural-1` pass on host
73F4A63FE57199A5ADE03552B367BC56FA1BCEA9F6D550DD3C1EE9918485BEFD.
The former checks each of three geometry-guided filter passes with independent
double arithmetic and filtered HDR composition, alongside raw transport,
temporal recurrence and existing world lifecycle gates. The latter uses the
production non-history filter resources and captures day/sunset with actual
camera checks and post-timing history counts. Other mod hashes are unchanged.
Pictures remain too dark under trees and at sunset; water is unfinished.
There is no matched high-sample convergence or paired pass-cost evidence. See
INDIRECT_LIGHTING.md for weights, rejection counts, exact scope and raw metrics.

## Temporal diffuse-light checkpoint (2026-10-15)

`qa-indirect-temporal-7` passes on host
296959B7EFB04DE59E06D536E7E5D6CAE685C8FA37EB92E18A7FA4F5DCAF147A.
CPU recurrence/rejection checks cover capped accumulation, actual camera
translation and a geometry/emitter reset. Raw source transport and existing
motion inputs are checked separately. See INDIRECT_LIGHTING.md for counts,
readiness/guide rules, retained failures and limits. Spatial filtering,
high-sample convergence, full motion/receiver support and cinematic acceptance
remain open. The following checkpoints describe older hosts and their scope.

## Visible diffuse-bounce checkpoint (2026-10-15)

Host FB605CA4955676047E466218CB74ECB95CA51F7BD354A1DD13BBB7AAF0871923
passes the visible/atmospheric reference (`qa-indirect-visible-2`), fallback-sky
visible reference (`qa-indirect-visible-fallback-1`), feature-off live material
reference (`qa-indirect-visible-feature-off-1`), and one-target FP16 natural capture
(`qa-indirect-natural-2`). Other mod hashes are unchanged. The CPU/source-PNG
reference verifies final HDR as well as raw transport before/after offscreen
emitter removal; see INDIRECT_LIGHTING.md for exact earlier/current scope.
That host has no temporal accumulation or filtering, and its noisy/dark natural
captures fail cinematic visual acceptance. Their wall cadence is not full-product
performance evidence.

The first natural GI run remains failed at its sunset camera gate. The revised
pose check measures actual inverse-view rotation separately from projection
displacement, keeping its 0.002 radian limit. Raw historical evidence is not
rewritten; a subsequent pass alone does not prove the old failure's exact cause.

## Earlier raw diffuse-bounce checkpoint (2026-10-15)

`qa-indirect-raw-6`, `qa-indirect-feature-off-2` and
`qa-indirect-regression-atmosphere-2` pass on JAR SHA256
92B6B67E5AB0580A953682E847295A71C42566C314AC832A97BD13E25256C17F.
The first independently compares raw secondary-ray geometry, source-PNG hit
radiance and diffuse receiver contribution before/after removal of a wholly
offscreen emitter (19/0 selected hits). The second verifies feature-off material,
AO, atlas, direct BRDF and reload behavior. The third runs the existing atmosphere,
32-phase lighting, leaf, reflection, far-origin, motion, DLSS and lifetime gates.
The other three mod binaries are unchanged in all runs. See INDIRECT_LIGHTING.md
for detailed scope, retained failures and raw metrics. Visible indirect lighting,
atmospheric bounce transport and accumulation/denoising were not implemented
on that historical host.

Raw diagnostic images must outlive the whole graph for end-frame readback:
history allocation prevents alias reuse but does not implement filtering.
Earlier overwritten readbacks are retained as failures. The first atmosphere
regression loses its lighting coverage after five jitter phases and remains a
failure. Synthetic QA now clears user input inside its verified disposable world;
the failure message also includes actual pose. The precise historical cause is
not established merely by the subsequent passing run.

## Leaf transport and camera audit (2026-10-15)

See FOLIAGE_TRANSMISSION.md for the independent CPU/source-texture transport
reference and the exact coverage limits. Integrated run 6, feature-off live
materials run 1 and surface GPU run 1 pass on DA77258A…97509. Raw evidence and
generated pack hashes are archived under their qa-foliage names. The clean local
offline JAR/native rebuild reproduces the tested hashes; the CPU surface contract
also passes. Logs and `foliage-clean-rebuild.json` are retained in evidence.
This is not independent-checkout reproduction or visual acceptance.

Earlier natural camera reports recorded a command, not the actual shader pose.
They cannot support fixed-view comparisons. Natural foliage run 2 validates the
actual shader eye/center ray every measured frame; camera input locking is scoped
to the marked QA world. Its timing includes the CPU guard and excludes timed GPU
readbacks. Geometry varies during measurement and no final-performance claim is
made. The old raw evidence is preserved unchanged.

## Reconstruction corrections (historical)

The initial compiler run exposed missing nested MixinExtras dependencies and
decompiler artifacts. Repairs restored exception variable references, explicitly
qualified the BindGroupLayout builder, restored attachment generic types, and
restored `(Object) this` casts used by Mixin classes. No visual feature was added
by these changes. Further review and runtime verification remain necessary.

The hardware probe initially exhausted LWJGL's stack allocator while allocating
the extension-property array. It now allocates that variable-sized array on the
native heap and releases it with try-with-resources. The corrected probe compiled
and ran successfully. No logical GPU device was created by this probe.
## Diffuse sampling quality (2026-10-16)

See INDIRECT_SAMPLING.md. Four-path means pass source-triangle/PNG checks before
and after offscreen-emitter removal, together with the existing integrated gates
and selected AgX display. The natural 1/16/1 comparison completes six trials
with exact geometry equality; its imagery still fails cinematic acceptance.
Sixteen-sample natural execution is not a substitute for source validation of
its full front/back path means; 64 has not been GPU-tested. Tolerances remain
unchanged. No FPS acceptance target applies.

## Static cloud prototype (2026-10-16)

See CLOUD_DESIGN.md for preserved failed density runs and exact scope. Source
field checks pass in qa-cloud-field-3; static day/sunset HDR composition passes
in qa-cloud-static-preview-1 on 1B4C750D…7499F3B. The cloud-disabled integrated
fixture qa-cloud-disabled-regression-1 passes on the same host with unchanged
non-Sulkan mod hashes. These are numerical/execution gates, not scattering,
world-shadow, moving-guide or cinematic acceptance. Inspected cloud images fail
the visual target. No current FPS requirement or further DLSS work is a gate
for the next visual milestone.

Later qa-cloud-billow-preview-2 passes the new 128-block density and composition
checks. qa-cloud-shadow-receivers-2 passes 306 independent source-density map
texels and 8,192 receiver cases per phase, including projection below clouds,
source integration inside, bypass above and periodic translations. Host
BB351A17…ABA5EE5C is shared with the retained failed receiver run; their external
GLSL differs and pack hashes are archived independently. The strict range failure
was fixed in probability interpolation, not by loosening validation. These checks
do not establish moving-camera behavior, scattering convergence or complete
cinematic quality. See CLOUD_DESIGN.md for numerical results and image limitations.

The full cloud-disabled regression qa-cloud-billow-disabled-regression-1 passes
on the same BB351A17 host, preserving the earlier scoped gates and other mod
hashes. Local clean runtime/source/native rebuild equality is recorded in
cloud-volume-clean-rebuild.json. Stable manifest serialization preserves array
order and semantic contents; independent generator processes now produce 92
byte-identical files. Independent-checkout reproduction remains open.

qa-cloud-shadow-canonical-1 passes the static day/sunset preview on the clean
rebuilt host/native pair with canonical serialization. Its 92 pack files match
the independent generator outputs byte for byte. The internal cloud-volume-v1
source checkpoint and per-file manifest preserve this state. The existing
whole-tree diff check still reports whitespace in files untouched by the cloud
change; that check is not claimed clean. New cloud shader/validator files were
separately inspected for trailing whitespace.

## Cellular clouds and camera anchoring (2026-10-16)

qa-cloud-cellular-2 passes density/period, 24,576 atlas interpolation evaluations
per phase, composition and shadow receiver checks. The earlier failed four
translated-density cases still replay unchanged; `precise` coordinate arithmetic
and explicit translated-noise readbacks pass without larger tolerances. The
noise asset regenerates byte for byte and has 528 distinct scalar voxel/channel
checks. MIT source/license provenance is retained in CLOUD_DESIGN.md.

qa-cloud-camera-audit-1 passes five static captures on 9796A949…7784476. Raw maps
and fixed-world density probes remain byte-identical through translation,
rotation and return. World environment lookup is invariant under pure rotation
and matches the baseline on return. Actual shader camera poses are checked;
this does not establish continuous motion, advection or proxy-vector correctness.

qa-cloud-cellular-disabled-1 passes on the preceding host. The latest host's
qa-cloud-cellular-disabled-2 fails the retained water primary/raster boundary
case at pixel 313740. No acceptance changed; replay remains diagnostic and the
failure remains open. Current clean artifacts and 96 generated pack files
reproduce locally, as recorded in cloud-cellular-clean-rebuild.json. The internal
cloud-cellular-v1 source checkpoint preserves this state, including the open gate.

## Water coverage interval revision (2026-10-16)

The later policy revision is explicit in WATER_COVERAGE.md: coverage acceptance
changes to a bounded CTS-inspired PARTIAL interval plus a matched source-plane
witness. Numerical position/normal/motion tolerances are retained. Old FAIL
reports above remain FAIL. The legacy replay and four rejected negative cases
are preserved separately. qa-water-coverage-1 passes on D954887E…CEA8E4F3, but
has zero uncertain-plane samples. Live GPU motion on the new fallback path
remains unexercised; the offline legacy case did not save motion inputs.

## Cloud scattering orders (2026-10-16)

qa-cloud-order-view-1 fails the parameter grid, with 256 rounded exp2-generated
column inputs; the failed run and actual buffers are retained. Its 6,144
radiances pass offline against actual inputs, which does not relabel the run.
qa-cloud-order-view-2 uses binary-exact constants and passes all day/sunset
gates on 51BF5D1B…C2B43F30. Each phase has 2,048 slabs, 6,144 radiance comparisons,
256 empty and 256 equal-angle cases. A closed-form slab oracle checks shared GPU
step weights/phase with analytically bounded midpoint solar bias. Independent
Python replay passes; three corrupted copies fail. This does not establish
heterogeneous transport convergence or exact multiple scattering.

Density/period, cloud shadow/receiver and composition checks also pass in the
run. Inspected images still fail cinematic acceptance (soft, uniform clouds).
Whole-scene A/B attribution is not made because source geometry differs from
the previous capture. Existing reconstruction is retained, with no new DLSS
feature or FPS target. IMAGE_QUALITY_PLAN.md records the active priority.


## Ambient comparison and open regression (2026-10-16)

qa-cloud-ambient-comparison-1 passes six frozen-geometry 0/1/0 day/sunset
captures on B209B62C...CCAA1E7. Local vertical-column ambient visibility gives
stronger dark/lit separation at sunset; the image remains too soft for cinematic
acceptance. See CLOUD_DESIGN.md and cloud-ambient-reference.json for the explicit
plane-parallel approximation and separately reported angular error.

qa-cloud-ambient-disabled-1 FAILS stage 7 in CameraMotionConformance, pixel 920942:
expected motion [0.8052361815874431,-0.22272064365713362], actual
[0.8984375,-0.24853516]. Expected plane point is [1.6579312802699064,96,
1.7271001933332606], reconstructed raster point [3.9999722941885913,
97.00003871653232,4.198390569976564]. The cause is not established. The failed
run and pack hashes are archived; later lifecycle/resize gates did not execute.
No retry or tolerance change closes this regression. Exact raw inputs at the
failing camera comparison must be investigated/preserved before further claims.

Both QA launchers now write soundCategory_master:0.0, per user request. Their
PowerShell syntax and the setting were checked. The preceding regression was
launched before this change and is not evidence of a silent runtime launch.

## Camera edge inputs and grazing water (2026-10-16)

qa-camera-edge-sweep-1 preserves an exact repeat of the quartz-vs-stone failure.
Legacy replay still fails. The revised bounded coverage gate passes that saved
case; four corrupted copies fail, and a hypothetical narrower 16-bit interval
rejects the saved edge. Source position/motion/normal data support the stated
8-bit edge explanation. This is a checker correction, not a renderer fix.

qa-camera-edge-sweep-2 and qa-camera-water-record-1 both FAIL later water
source-position comparisons. The 32-phases-per-direction motion sweep has not
passed. The latter run bounds camera X to 24..25.25 (including the failing
view) and persists completed phase reports incrementally. Its failure record
contains actual frame, GPU readbacks and all 19,208 source water triangles.
Independent replay measures 0.00348422 m position error and 0.00274969 pixel
motion error. A candidate projected-vertex rounding interval contains the GPU
depth, but no water error-bound/acceptance change is justified or applied yet.
All original failures remain archived. CAMERA_MOTION_COVERAGE.md explains the
exact scope and next investigation. Both newer runs retain master volume 0.0.

## Cloud detail profile (2026-10-16)

qa-cloud-detail-comparison-1 passes six 0/1/0 static day/sunset captures on
4F1D2A43...A1CA1CF1 with identical frozen source geometry. Profile 1 adds the
same owned atlas at a 128 m tile for erosion; model metadata distinguishes
cellular_detail_v1 from historical density. The source PNG is unchanged.
14 captured field buffers cover 24,576 base and 24,576 detail noise evaluations
per capture plus the existing density/period, shadow/ambient/receiver,
scattering and composition gates. Original numeric limits remain unchanged.

Independent replay passes both candidate phases. Legacy failed inputs still
fail four translated-density cases. Baseline-return density and world shadow
maps are byte-identical; active options and source geometry are independently
checked in cloud-detail-comparison-analysis.json. Inspected images have smaller
lobes/less uniform edges, but do not meet complete cinematic acceptance.
Moving-cloud motion/proxy behavior and heterogeneous quadrature convergence
remain open. The separate grazing-water position failure and incomplete camera
phase sweep also remain open; this static pass does not relabel them.
