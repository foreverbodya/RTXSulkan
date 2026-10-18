# NGX session adapter

The original adapter source is MIT licensed. NVIDIA SDK inputs remain external;
SDK/host distribution compatibility remains an open release gate.

`DlssSession` opens a native context on Minecraft's negotiated Vulkan device.
It queries Quality dimensions, records feature creation/evaluation into the
caller's VkCommandBuffer, and retires through the engine's queueForDestroy
completion mechanism. Normal adapter calls do not submit a separate Vulkan
queue or wait for device idle. The world-free diagnostic owns the explicit
submissions and device-idle waits needed for its synchronous CPU comparisons.

Native context IDs are registered handles, not unchecked addresses from Java.
Each context owns its parameter map and DLSS feature. Contexts on the same
device/runtime share SDK initialization and capability parameters. Native SDK
calls are serialized because NGX is not thread-safe. The last context destroys
capabilities and shuts down NGX. The capability-only probe refuses to replace
an active session. Shared-context overlap and failure injection are not yet
validated; do not claim them ready from the single-context diagnostic.

The current image contract is deliberately explicit:

| Input | Format | Extent |
|---|---|---|
| Linear HDR color | RGBA16_FLOAT | Quality input |
| Reversed depth | R32_FLOAT | Quality input |
| Previous-minus-current motion, input pixels | RG16_FLOAT | Quality input |
| Exposure | R32_FLOAT | 1x1 |
| Output | RGBA16_FLOAT | Output resolution |

All images are live single-mip, single-layer 2D images in GENERAL layout. NGX
receives the image, view, format, extent and color-aspect range. The caller owns
image transitions and GPU dependencies; sampled/storage usage must support NGX.
Jitter is in input pixels. The adapter currently fixes pre-exposure/exposure
scale to 1; dynamic exposure integration remains work. Alpha reconstruction is
not enabled, so diagnostics and presentation must not assume output alpha is
defined. A retiring Java session cannot record more work.

The GPU diagnostic checks constant RGB frames, two reset frames and two history
frames, including red=2 above display range. It pre-fills output with NaNs,
checks every RGB pixel after actual GPU copies, and retires the feature/context.
It proves basic execution and simple HDR/reset behavior. It does not validate
moving geometry, jitter, transparent objects, nonuniform detail, overlapping
sessions, in-flight retirement stress, visual quality or frame rate.

## Renderer generation

`DlssReconstruction` owns a fixed-size output image/view and one session.
It obtains the Quality input extent from NGX, validates all four input formats
and extents before recording, and creates the feature on its first recorded
frame. Creation and evaluation use the caller's command encoder with memory
dependencies before and after NGX. The wrapper contains no queue submission,
CPU readback or device-wide wait. The host must supply GENERAL-layout images
and replace/retire the generation when output size changes.

Retirement first invalidates Java access and schedules native release on the
encoder. Its completion closes the owned output view and image. Output-size
replacement, overlapping generations and in-flight retirement still need
separate validation. The full-pixel constant-HDR diagnostic now exercises this
renderer generation rather than recording NGX directly from diagnostic code.
Diagnostic-only submission and device waits remain outside the wrapper.
