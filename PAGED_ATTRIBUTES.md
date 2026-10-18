# Paged terrain ray attributes

## Compact quad payload update, 2026-10-17

The production page allowance is also superseded by the bounded per-device
policy in GPU_GEOMETRY_BUDGET.md (heap fraction, absolute ceiling and shared
ownership through retirement). The original eight-page/512 MiB description
below is historical; explicit small-budget tests still use that constructor.

The supplied helper now reads two header-selected payloads: the legacy 32-byte
float vertex plus explicit indices, and the new 28-byte packed vertex with
canonical quad topology. Production uses the compact encoding. It retains the
original position word pair, base tint, UV, light/flags, material/wind and float
AO. The shader reconstructs position bits and derives the same triangle indices
from primitive ID. Vertex count, triangle count, opacity and the directory
contract are retained. Large meshes approach a 26.3% payload-byte reduction;
header/alignment costs make the exact small-mesh ratio different.

Compact payloads cannot serve directly as Vulkan float BLAS input. The existing
float/index build path now supplies temporary build buffers, released by the
encoder completion callback after construction. Legacy payloads keep their
shared BLAS input path for compatibility and comparison. Permanent attribute
pages retain only the compact form. There is an upload/build-time cost to this
tradeoff; do not claim an FPS gain or reduced total peak VRAM from payload size.

`qa-gpu-compact-surface-1` passes actual GPU hit decoding through two pages in
both payload modes, for source strides 20/24/32 (36 rays total). Position,
normal, barycentrics, UV, tint, AO, light, wind and material agree with the CPU
reference; native buffers return to zero after the world-free check. Broader
source/lifecycle and large-distance checks are separate requirements. The
historical 32-byte/shared-BLAS description below refers to the earlier layout.

2026-10-15. The first natural forest/river run at eight chunks exposed an actual
capacity failure: `qa-natural-world-2` stopped at 66,737,152 bytes used, requesting
another 502,224 bytes. The former 64 MiB SSBO also encoded its word offset directly
in Vulkan's 24-bit instance custom index. Raising its capacity alone would break
that address contract.

## Address contract

`RayAttributeArena` now owns a stable 16 MiB SSBO directory and up to eight
64 MiB device-local payload pages. Pages are allocated on demand, retained for
reuse, and bounded at 512 MiB total payload capacity. The directory supports
1,048,576 live entries. Its allocation word offset remains the instance custom
index, within the 24-bit limit; it no longer points directly to mesh payload.

Each 16-byte entry stores a device address (low/high uint), serialized payload
byte count and entry layout version 1. GLSL uses a readonly buffer reference to
decode that mesh. The directory is the descriptor-bound resource; payload pages
are accessed through already-negotiated buffer-device-address functionality.
`GL_EXT_buffer_reference_uvec2` avoids requiring shaderInt64 solely for pointer
construction; see the [Khronos extension](https://github.khronos.org/Vulkan-Site/glslext/latest/glslext/ext/GLSL_EXT_buffer_reference_uvec2.html).
No additional device or queue is created.

This changes the experimental raw attribute ABI. Packs using the supplied
`sulkanRayAttributes(instanceCustomIndex, ...)` helper retain its signature;
custom code directly indexing the former mesh SSBO must use the new directory.
`Allocation.headerWord()` now means directory word offset. The serialized mesh
header and 32-byte vertex layout are unchanged. BLAS construction and attribute
decoding read the exact same page bytes, without duplicate geometry uploads.

Directory slot allocation is independent of payload page/range allocation.
Ranges and slots are immutable while live. Existing encoder completion callbacks
release retired allocations; stale or duplicate releases are rejected. Slots are
not reused while earlier GPU work can reference them. Transfer copies publish
both directory entry and payload before AS construction and shader access.
Closing the arena destroys all pages and its directory after reader completion.
There are no new production queue submits, readbacks or global idle waits.

The payload budget is explicit rather than unlimited. Fragmentation and requests
over one page can still exhaust the bounded allocator. Larger draw distances and
extended travel need further budget, fragmentation and retirement measurements.
Directory allocation adds 16 MiB beyond reported payload capacity; AS storage,
targets, NGX and Minecraft allocations are additional VRAM consumers.

## Evidence

`qa-attribute-paging-gpu-1` passes real ray queries for 20/24-byte source vertices
with the sampled mesh forced onto the second tiny payload page. Barycentrics,
UV/tint, lighting, material, wind, position and normal match the CPU reference;
the native-buffer counter returns to zero. Its JAR is
`6D4995E0AE87A3B292F7677380510F48D63BE89C1EAFC83ED196F7B026BA5003`.
The CPU range probe passes 10,000 randomized allocation/release operations,
alignment, coalescing, stale release rejection and a failed non-mutating
`tryAllocate`. These checks do not prove unlimited-world capacity.

`qa-natural-world-3` captures a normal forest/river world using three payload
pages instead of failing at 64 MiB. Run 4 adds start/end AS statistics and an
explicit inactivity policy; details and visual rejection are in NATURAL_WORLD.md.

On the later JAR `FDF0B46A639C73BFC3F95979D51E221DB8C6D395FF43F0A08100F2653C44EDB3`,
`qa-attribute-paging-gpu-2` repeats the two-page query check and
`qa-attribute-paging-integrated-1` passes alpha traversal, all 32 lighting jitter
phases, offscreen reflection and removal, +/-16M rebasing, actual world DLSS,
resize/reload and deferred retirement (zero live native buffers). Resize logs
contain recoverable Minecraft swapchain-out-of-date warnings; subsequent
size-specific readback and lifecycle checks pass. This is not a claim of a
warning-free run or completion of all long-session synchronization cases.

`qa-attribute-paging-materials-1` passes the separate no-atmosphere live-atlas
material comparison before and after reload on that same JAR. Clean offline
JAR and native rebuilds match their tested hashes; logs and scope are recorded
in `evidence/attribute-paging-build-repeat.json`. Independent checkout/machine
reproducibility remains unverified. Previous arena/decoder sources are retained
as `.txt` evidence for review alongside the failed capacity run.
