# GPU terrain attributes: compact data and bounded shared pages

The CPU storage correction exposed a separate 512 MiB GPU attribute limit at
16 chunks (`qa-terrain-storage-r16-1`). This is an actual preserved failure.
CPU payload/decode budgets remain as documented in TERRAIN_GEOMETRY_STORAGE.md.

## Remove permanent redundancy first

Production attribute payloads now retain seven words per accepted vertex:
original packed position pair, base tint, UV, light/flags, material/wind, AO.
The two triangles of each quad derive their canonical indices from primitive
ID. The legacy eight-word float/indexed payload remains supported by the helper
and is exercised separately. See PAGED_ATTRIBUTES.md for the byte layout.
There is no triangle/material/distance cutoff or reduced position precision.

`qa-gpu-compact-r16-1` passes day and sunset with the original eight-page
512 MiB limit. The loaded scene contains 7,533,268 triangles, 5,821 nonempty
meshes and 2,903 nonempty sections. Attribute payloads use 421,956,144 bytes
in seven 64 MiB pages; BLAS/TLAS storage separately uses 487,531,008 bytes.
The CPU source cache also contains empty sections. The corresponding former
76-byte/triangle encoding alone would exceed 512 MiB before headers/alignment.
This is successful 16-chunk evidence, not evidence that 32 chunks fit that cap.

## Shared page allowance

The default production allowance is now the smaller of one quarter of the
largest DEVICE_LOCAL heap and 3 GiB, rounded down to 64 MiB pages. This is a
static physical-heap policy; it does not measure OS/other-process memory use.
The allowance covers attribute pages across all live arenas of the same device,
including old renderers waiting for a GPU fence. It is not multiplied per pack
reload. The directory is separate (16 MiB per normal arena), as are AS storage,
scratch/build/staging inputs, render targets, NGX, Sodium and driver allocations.
The remaining three quarters are headroom in the policy, not reserved memory.
No claim of a whole-process VRAM limit follows from this attribute allowance.

Each new page reserves ownership before native allocation. Allocation failure
refunds the reservation. Actual page destruction releases it. The directory
still uses 24-bit instance IDs and uvec2 device addresses; page-local ranges
remain at most 64 MiB. Aggregate used bytes now use long accounting so a 3 GiB
allowance cannot wrap a signed 32-bit counter.

If another arena owns the required allowance, synchronization preserves pending
work and retries on a later frame. If an arena needs space occupied by obsolete
meshes, it can first record a retirement-only transaction, leave replacements
pending, and build them after completion releases old ranges. In that intermediate
state the old mesh can be absent; it is not a permanently omitted mesh or a
claim of artifact-free interactive replacement. Existing loading/history state
tracks pending work. A truly oversized settled scene still fails explicitly.

Natural capture readiness now additionally checks that the ray scene contains
all cached nonempty sections, meshes and triangles once pending work is zero.
This prevents accepting a reduced scene merely because the renderer stayed on.

## Specific ownership checks

- `ray-page-budget-cpu-1.json`: 50,000 reference allocation/release operations,
  failed reservations without ownership changes, peer waits, hard limits,
  heap policy and 3 GiB arithmetic. This is CPU evidence only.
- `qa-gpu-page-retirement-1`: actual one-page/128-byte GPU arena. Replacing its
  mesh records retirement first, a retry before submission waits, and building
  resumes after the real completion callback. A second shared-budget arena
  waits for the first arena's actual upload/retirement, then resumes. No explicit
  device idle is used in this pressure check. All native buffers and shared
  reservations return to zero. The existing hit-decoder subcheck still uses
  diagnostic idle/readback and validates both payload formats, 36 rays total.

The pressure check's runtime is
83CC6A7858180633C915FDFC6E1CFC7AE3E2C556C127CB6D0E783CF45B836AFF.
The subsequent source adds natural-scene completeness counts without changing
the allocation policy or payload decoder. Actual 32-chunk loading, broader
travel, pack reload at scale, total memory pressure and visual/gameplay acceptance
remain required. The user instance has not been updated.

## Current production-code correspondence

`qa-gpu-page-budget-source-1` passes the broad synthetic source/lifecycle run on
191A613698F0D332CB20738A01E9BAD9881004DB6542E6C96CAD456C16D0CCBC.
The current distance-transition candidate is
347E09535EFDD9DF9AAC332C0A4C5947FACC11FA4BB730AB3FC33574A0AD8F42.
`gpu-budget-production-bytecode-1.json` compares every uncompressed JAR member:
all 200 non-diagnostic entries, including production classes, shaders and
configuration, match exactly. Only QaVisualWorldValidation.class changes to
add the dedicated 16-to-32 and same-pack-reload path. This preserves the scope
of the earlier source run; it is not an independent rebuild or gameplay proof.

`qa-gpu-budget-distance32-1` exercises the actual option change in one frozen
world, with Sulkan requesting a terrain rebuild. Capture readiness also waits
for Sodium's terrain-complete predicate and all cached nonempty sections/meshes/
triangles to reach the ray scene. The final phase reloads the same unchanged
pack at 32 and requires the source geometry hash to match and one live arena.
Until its terminal report is verified, this scenario is not accepted.

## Terminal 16 → 32 and reload result

The preceding pending observation is superseded by the terminal PASS in
`qa-gpu-budget-distance32-1` (exit 0). The runtime is 347E0953… above.
`draw-distance32-analysis-1.json` independently recomputes source digests,
nonempty section/mesh/triangle counts and compact payload sizes from retained
source manifests, and checks the actual limits. The old running status is kept.

Both 32-chunk captures contain exactly 29,400,438 triangles, 12,361 nonempty
sections and 24,527 meshes. Their source geometry SHA-256 is identical:
046b3bb558dd6bd3472aed1af22e812df8474b919c471ed6af818397ff67964e.
Attribute payloads use 1,646,816,960 bytes in 25 pages (1,677,721,600 bytes),
below the shared 3,087,007,744-byte limit, with one arena and no pending sections.
BLAS/TLAS requested storage is separately 1,907,625,472 bytes. CPU payloads use
189,281,606 bytes and the decode cache 67,057,400 bytes; these are array counters,
not total process memory. No triangle or accepted material was omitted.

This is one frozen natural scene, an actual 16-to-32 option change, then a
same-pack renderer replacement. It is not long travel or all-world acceptance.
Large-scene budget waits were zero; the real pressure/retirement paths were
exercised by the separate tiny GPU case, not by pretending this scene hit them.
The 32-chunk sunset mean frame cadence is 175.7 ms and post-reload 179.5 ms:
still unplayable. Long AS loading, total memory pressure and visual polish remain
open. No current changes have been installed in the user instance.
