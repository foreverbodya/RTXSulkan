# DLSS integration audit

Status: NGX initialization and capability queries execute successfully on
Minecraft's existing RTX 5070 Vulkan device. The SDK reports DLSS available and
requests 1707x960 input for 2560x1440 Quality output. Feature creation and actual
GPU evaluation of four controlled HDR frames now pass, including repeated
history and reset to a color above display range. The engineering world pack
now routes actual lower-resolution world HDR through DLSS and displays its
output before rendering the hand and UI. A guarded 1707x960-to-2560x1440 world
run and 1280x720-to-1920x1080 replacement pass basic checks. Complete object
motion, temporal reconstruction quality and production performance are unfinished.

## Vulkan adapter

The official SDK exposes creation and evaluation on an existing VkDevice and
VkCommandBuffer. Its resource helper describes VkImageView, VkImage, format,
subresource range, dimensions and read/write use. Use Minecraft's device and
submission lifecycle. The adapter must own NGX parameters and feature handles,
record work on the engine command buffer, and retire resources after GPU use.

Sources:
- https://github.com/NVIDIA/DLSS
- https://raw.githubusercontent.com/NVIDIA/DLSS/main/include/nvsdk_ngx_helpers_vk.h
- https://raw.githubusercontent.com/NVIDIA/DLSS/main/doc/DLSS_Programming_Guide_Release.pdf

## Required frame contract

HDR input color before presentation, depth, per-pixel motion, render/output
dimensions, pixel-space jitter, exposure and explicit history reset. Query the
SDK's optimal Quality dimensions rather than treating a fixed ratio as the
integration contract. World changes, resize, resource reload and camera cuts
must reset accumulated history.

The existing frame UBO has previous/current matrices and camera displacement.
World sample jitter and lower-resolution targets now exist. Complete object
motion is absent. A camera-only reprojection is insufficient for moving entities,
animated vegetation and water. These remain implementation gates.

## Native build and distribution

On 2026-10-14, cmake, cl, clang, clang-cl and zig were absent from PATH; the
standard Visual Studio installation folder was absent. A reproducible native
toolchain was subsequently assembled in the workspace on 2026-10-15 using
Microsoft's MSVC 19.44.35229 packages and Windows SDK 10.0.26100.6584. No system
toolchain was installed. The compiler and NVIDIA runtime signatures are valid.

`tools/native-dependencies.lock.json` pins 8 external archives and 29 SDK/JNI
inputs. `Get-NativeDependencies.ps1 -ToolchainRoot ... -SdkRoot ...` downloads or
verifies them outside the repository. The OpenJDK 25 JNI headers are external
inputs because Prism's bundled runtime does not include them. Build the probe:

```
./tools/Build-NgxProbe.ps1 -ToolchainRoot C:/path/to/tools -SdkRoot C:/path/to/sdk -JniRoot C:/path/to/sdk/jni
```

Two rebuilds of the final probe in this checkout produced identical DLL bytes
with compiler/linker `/Brepro` and incremental linking disabled. This is not yet
an independent clean-checkout or cross-directory reproducibility check.

Pinned NVIDIA SDK commit: 374959484e79a640feaba44c93ac8cfb0a03f5b5, API 21;
development runtime file version 310.9.1.0. Its SDK distribution stays external.

SDK redistribution terms and the project's retained GPL-3.0 require a concrete
compatibility review. The NVIDIA license restricts making SDK components subject
to an open-source license. No determination has been made that a separately
loaded adapter resolves those requirements. No NVIDIA headers, libraries or
binaries have been added to this repository for redistribution.

The original probe source has its own MIT license under `native/dlss`; that does
not establish permission to distribute its compiled SDK-linked combination
with the GPL host. Distribution review remains open.

License source: https://raw.githubusercontent.com/NVIDIA/DLSS/main/LICENSE.txt

## Acceptance evidence

### Initialization and negotiation

The initial real launch initialized NGX, but availability was 0 because the
Minecraft device did not enable the SDK's required NVX extensions. The full
failure is retained in `evidence/dlss-capability-1`. The opt-in Sulkan feature
set now requests the API-21 extensions returned by the SDK:

- VK_NVX_binary_import
- VK_NVX_image_view_handle
- VK_KHR_buffer_device_address
- VK_KHR_push_descriptor

It uses Minecraft's existing Vulkan-1.2 feature structure to enable buffer
device address. The diagnostic checks that the loaded SDK's API and returned
extension set match this pinned contract. `-Dsulkan.dlss=true` requests these
device features only; it does not enable reconstruction of game frames.

The repeated probe succeeds with both DLSS and ray-query feature sets enabled.
Run it in a new marked disposable instance:

```
./tools/Run-DlssProbe.ps1 -InstanceMods C:/path/to/mods -QaDirectory C:/path/to/new-qa -RuntimeDirectory C:/path/to/sdk/lib/Windows_x86_64/dev -EnableDlss -RayTracing
```

Explicit result codes cover initialization, capability retrieval, availability,
Quality dimensions, parameter destruction and shutdown. This is a world-free
capability test, not feature creation, neural reconstruction or a benchmark.

### Remaining sequence

1. Extend the owned NGX context's verified basic execution to nonuniform images,
   motion/jitter/exposure, overlapping contexts and in-flight retirement stress.
2. Add lower-resolution world rendering, correct per-object motion, jitter,
   exposure and history resets; retain output-resolution UI/hand handling.
3. Verify animated gameplay and measure the completed cinematic pack at 1440p.

### Actual GPU frame evaluation (2026-10-15)

`DlssSession` records feature creation/evaluation into Minecraft's command
buffers and defers retirement through its encoder. The original adapter owns
its SDK/parameter/feature lifetimes and serializes SDK calls. See
`native/dlss/SESSION.md` for the image contract and unverified cases.

Run the world-free GPU diagnostic with `Run-DlssProbe.ps1 ... -EnableDlss
-RayTracing -FrameEvaluation`, using the SDK's external `rel` runtime folder.
Evidence `evidence/dlss-frames-6` checks every RGB pixel of four 2560x1440
outputs. The inputs are constant linear colors (0.18,0.36,0.72) then
(2.0,0.12,0.04), with resets on frames 0 and 2. Output begins as NaN sentinels.
Maximum absolute RGB errors are 0.00173831 and 0.00390625. The allowed error is
max(0.005, 0.5% of the expected channel). Feature release, parameter destruction,
capability destruction and shutdown return success; the registry has zero
contexts afterward. Alpha is not tested or promised reconstructed.

The first development-runtime frame failed because its visible SDK watermark
changes output pixels. Its full-frame capture is retained in
`evidence/dlss-frames-3`. The release runtime removes that overlay; the verifier
still checks the entire image rather than excluding its former location.
The first attempted run also detected an old JAR left by a compile failure;
the runner now checks frame-diagnostic availability before launch. Failures
remain recorded. Uniform frames do not establish gameplay reconstruction quality.

Record SDK version/hash, negotiated required device extensions, initialization
and feature availability results, creation settings, and actual evaluation.
Then verify motion scale/direction, depth convention, jitter, HDR exposure,
disocclusions, transparency, resizing and shutdown. A nonzero handle or a
successful call alone does not establish reconstruction quality or 100 FPS.

### Camera motion groundwork (2026-10-15)

The opt-in RayLighting `-TemporalInputs` fixture writes static-surface motion
in RG16_FLOAT, previous minus current in input pixels. It invalidates vegetation
and surfaces whose captured terrain depth does not match world depth. This is
camera-only motion; it does not provide object/wind/water/hand velocities.

CPU checks cover a 32-phase Halton sequence, projection jitter at three extents
and 27 camera translation/rotation cases.
The real-world motion check independently projects known quartz geometry and
tests stone occlusion analytically. Evidence: `evidence/camera-motion-4`.
Continuous small steps and moving history-buffer association are additionally
checked in `evidence/camera-motion-5` and `evidence/camera-history-6`.
The later world integration connects these static-surface inputs to a
lower-resolution target and the native session; it remains an engineering fixture.

### Opt-in world sample jitter

`-Dsulkan.temporalJitter=true` now applies a 32-phase Halton offset to the
effective world projection passed to Minecraft's projection buffer. It is
enabled only while a native pack owns the world scene. The camera-state matrix
used for culling is unchanged; hand projection is captured separately.
`Run-QaWorld.ps1 -RayLighting -TemporalInputs -TemporalJitter -DlssFeatures`
checks this path on a disposable world.

The frame uniform block is now 1,088 bytes. Its existing fields retain their
offsets; `CurrentAndPreviousJitter` is appended at byte 1,072. Raster and compute
declarations share `GraphFrame.MEMBERS`. Motion reprojection reconstructs depth
with jittered matrices and adds current-minus-previous sample jitter to remove
sample offsets from the NGX motion convention. The isolated CPU probe covers
864 jittered translation/rotation cases in addition to the unjittered cases.

This switch intentionally exposes raw frame-to-frame subpixel displacement:
no temporal reconstruction consumes it in ordinary packs. It defaults to false;
packs declaring reconstruction enable their own jitter. Complete object motion,
gameplay quality and verified NGX jitter orientation on nonuniform reconstructed
imagery remain gates.

### Pack reconstruction boundary

The optional `reconstruction` manifest object requires type `dlss_quality`,
distinct `color`, `depth`, `motion`, `exposure` resources and a `display` shader.
Color must equal the HDR `present` resource. Formats are RGBA16_FLOAT,
R32_FLOAT, RG16_FLOAT, R32_FLOAT respectively; the first three match world
extent and exposure is 1x1. Scheduling includes all inputs and retains them
through the reconstruction boundary. The pack's display fragment samples
`Source` and applies tone mapping after reconstruction.

`WorldReconstruction` owns a persistent world target and SDK-sized output
generation. It asks NGX for Quality dimensions, uses the smaller target only
during world rendering, reconstructs before the hand, and writes the real
output target at window resolution. Resize replaces the generation; the prior
world/output textures retire with the native context. The wrapper creates no
per-frame targets and calls no per-frame global GPU wait.

For the development-only disposable world check, provide the external adapter
and SDK release runtime explicitly:

```powershell
./tools/Run-QaWorld.ps1 -InstanceMods 'C:/path/to/mods' -QaDirectory 'C:/path/to/new/qa-world' -RayLighting -WorldDlss -NativeLibrary 'C:/path/to/sulkan_ngx_probe.dll' -RuntimeDirectory 'C:/path/to/sdk/lib/Windows_x86_64/rel'
```

Device DLSS features are negotiated at launch. Actual SDK/runtime distribution
is still gated by license review. The fixture uses constant exposure and
camera-only motion; water, foliage animation, moving entities and production
visual fidelity require further work.

The optional reflection graph now sends `ray.composite.hdr` to the same SR path.
Real-game combined and ordinary-light regression evidence, including resize and
the stale `SkyRenderer` constructor-target correction, is recorded in
`REFLECTIONS.md`. This does not establish reflected-motion correctness or RR.
