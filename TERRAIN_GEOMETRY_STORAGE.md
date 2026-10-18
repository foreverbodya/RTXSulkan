# Bounded terrain geometry storage

The user's 32-chunk shader pause was a CPU terrain-cache failure. A separate
normal-world Y260 fixture reproduced it at 16 chunks before reaching any cloud
checks (`qa-cloud-interior-height260-2`, original 512 MiB array limit).

## Representation

The former mesh permanently owned packed Sodium words, decoded float positions
and canonical quad indices: 50 bytes/vertex for the material format. A 12-chunk
scene retained 330,293,000 bytes for 3,302,930 triangles. Geometry was duplicated
in representation, not necessarily duplicated in content/owners.

TerrainMesh now owns a lossless byte-lane encoding of every accepted packed
vertex word. DEFLATE level 1 compresses that encoding; incompressible data keeps
the raw lane encoding. Decoding restores all word bits, then derives the same
float position bits and canonical indices. There is no geometry-distance cutoff,
material omission, precision reduction or ray-setting change in this correction.
GPU attribute format, BLAS construction and deferred GPU retirement stay intact.

Decoded arrays use a FIFO cache bounded separately from payloads. On a 16 GiB
Java heap, the previous overall 512 MiB allowance is divided into 448 MiB of
owned payloads and 64 MiB of decoded arrays. Smaller heaps retain the old
maxHeap/4 total allowance and the same 7:1 split. Eviction discards decoded
arrays only. Immutable payloads allow decoding again after eviction or reload.
Replacing/removing a section releases its decode-cache ownership; world clear
clears that cache. Removed geometry is not kept merely to fill the cache.

A ReadView pins immutable data for one serialization/interpolation operation.
It survives cache eviction, and supports a single mesh larger than the decode
cache without retaining it there. Returned views, active upload batches, codec
work buffers, source references held by callers, GPU resources and diagnostics
are outside the two retained-array counters. These are not a whole-process
heap/VRAM cap. The unchanged payload cap can still reject a sufficiently large
or incompressible world. GPU attribute pages had their separate 512 MiB limit;
this CPU change did not solve or silently raise it. The subsequent compact
payload and bounded shared GPU allowance are documented in GPU_GEOMETRY_BUDGET.md.

## Verification so far

- `evidence/terrain-storage-cpu-1.json`: standalone Java, -Xmx256m, 46 checks
  including reacquisition. 6,669,104 packed words, 2,535,504 float position bit
  comparisons and 1,267,752 indices agree. Formats 20/24/32, random/structured
  words, legacy/material fields and source-buffer lifetime are covered.
- The same CPU probe exercises decode pressure, pinned views after eviction
  and clear, an uncached oversize mesh, malformed dimensions/AO and corrupted
  DEFLATE checksum rejection. Decode retention stays within its 8 MiB budget
  under that smaller heap. This is not a natural-world compression benchmark.
- `qa-terrain-storage-source-1` passes actual Sodium 20/32-byte encoder checks,
  ray/material/source transport, reflections, atmosphere, DLSS, resource reload,
  resize and retirement. Ray depths/path counts are unchanged. The engine emits
  recoverable swapchain-out-of-date warnings during intentional resize; the
  required resize and final markers still pass.

Runtime SHA256 for these GPU checks:
CCA2037071F46B4482CCAA82C7608DAA3B6C78C8DA9C94C0E8230CA4C5646C92.

## Natural-world results and next limiting resource

`qa-terrain-storage-r12-1` passes day/sunset cloud, source-density, composition,
reconstruction and distant-world checks. Its frozen natural world contains
1,094 sections and 3,261,024 triangles. At the day capture, payloads own
22,950,402 bytes and decoded arrays retain 66,613,800 bytes, below their
67,108,864-byte cap. The equivalent former expanded arrays for exactly this
captured geometry would own 326,102,400 bytes. The new two counters sum to
89,564,202 bytes, about 72.5% below that derived expanded-array baseline.
This is not total Java heap or an exact cross-run image/FPS comparison.
The older 12-chunk case has 19 more sections and a different source hash.

`qa-terrain-storage-r16-1` no longer stops at the CPU-cache budget. It reaches
eight GPU attribute pages, then stops at the separate attribute payload budget:
used 536,603,840 / 536,870,912 bytes. This terminal failure is preserved.
The shader still pauses; the user's large-distance bug is NOT fixed yet.
The next step must manage GPU attribute/acceleration-structure memory and
their reload/travel lifetimes, including bounded headroom for pending readers.
Simply increasing the CPU limit would not have addressed this second limit.

Subsequent progress: compact GPU payloads pass the same 16-chunk fixture while
keeping the original 512 MiB allowance. See GPU_GEOMETRY_BUDGET.md for exact
counts, the subsequent shared page policy and its separate verification scope.

32 chunks, longer travel, geometry replacement under memory pressure and total
CPU/GPU memory behavior still need actual evidence. This is a
stability change; cloud/canopy/material/water quality, gameplay speed, source
provenance/licensing and release acceptance remain unfinished. The user's
instance has not been updated.
