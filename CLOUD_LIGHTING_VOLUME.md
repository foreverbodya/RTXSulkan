# Lighting-only volume-noise lookup

Development work on the unusable preview, 17 October 2026. Opt-in through
`Create-RayLightingPack.ps1 -CloudLightingVolume`, or the matching QA runner
switch. This has not been installed in the user's instance and does not close
the cinematic preview milestone.

## What changes

An added compute pass copies the existing own 128-cubed RGBA8 noise atlas into
a 130-cubed volume with one periodic apron voxel on each side. Storage costs
8,788,000 bytes, counted by the normal graph resource budget. Copying is done
each frame through the existing graph lifetime, not through a second persistent
cache or an unbounded world allocation.

Cloud solar and ambient lighting density lookups use the volume's hardware
trilinear filter. View density, refined optical columns, alpha/proxy guides,
world cloud shadow maps and their existing source-reference checks retain the
explicit eight-corner interpolation. Solar/ambient quadrature counts, three
scattering orders, diffuse bounces/paths, exposure and native DLSS are retained.
This is an approximation of the lighting lookup, not bit-exact filtered noise.

The original compute path ignored a pass's `linearReads` declaration. It now
selects the existing graph linear sampler per binding, and checks device linear
filter support for the requested format before creating the compute pipeline.
Ordinary bindings retain their previous sampler policy.

For standard layout dimensions, the two lighting-noise scales have exact 4m
and 1m cells. Their coordinates are derived together from the same split fine
lattice. The relation is `coarse = (fine + 0.5)/4 - 0.5`; signed floor division
is implemented by a signed shift and low-bit remainder. Other layout dimensions
retain the general split-lattice calculation. World coordinates are not snapped
to camera tiles and the visible-density path does not use the new approximation.

## Measurements and fidelity checks

The existing frozen-world comparison runs off/on/off in day and sunset, with
identical source geometry across all six captures, actual GPU timestamps and
unchanged lighting features. The additional field capture records 8,192 exact
voxel/apron checks (including all eight corners), filtered coarse/detail samples
and lighting-density approximation errors. Existing explicit-density source
budgets are unchanged; approximation errors are reported separately.

`qa-cloud-lighting-volume-1`: initial RGBA8 volume, separate coordinate lookups.
All six captures passed. Maximum filtered-noise difference was about 0.0021;
this must not be confused with an exact copy failure. With actual captured
environment radiance, fixed baseline exposure and tone mapper 2, maximum SDR
channel change was below 0.05 digital count out of 255 in both phases. Environment
alpha was identical. These are component comparisons, not full-frame visual or
movement acceptance.

`qa-cloud-lighting-volume-2`: RGBA32F storage experiment. Exact sampled voxel
copy still passed, while filtered-noise error remained about 0.0021 and the
day cloud-view pass became slower. Increasing storage precision did not solve
the observed filtered-coordinate error. This refutes the initial storage-
precision hypothesis. RGBA32F is not retained.

Vulkan explicitly allows filtered coordinates to snap according to
[`subTexelPrecisionBits`](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceLimits.html).
The observed behavior is consistent with finite filtering precision; these
experiments do not identify the driver's exact internal arithmetic.

`qa-cloud-lighting-volume-3`: RGBA8 with shared coordinate calculation. Final
analysis records cloud-view GPU times of 25.43 -> 18.03 ms by day and
107.49 -> 75.94 ms at sunset; cloud environment is 19.43 -> 12.62 and
110.61 -> 87.73 ms. The returning controls remain in the report. Whole-frame
wall cadence is about 100 -> 86 ms by day and 271 -> 217 ms at sunset,
still unsuitable for gameplay. The fixed-size copy pass costs about 0.04 ms.
Filtered-noise maximum difference is 0.002087; lighting-density difference is
9.72e-5. Existing explicit view-density source error stays below 5.95e-8.
Fixed-exposure environment SDR differences stay below 0.064 counts out of 255.
Sunset alpha differs at 843 texels from the first control, but the enabled
capture equals the returning control: this variation is not unique to the
cache. The local color-study targets are p99.9 <= 1 and maximum
<= 3 SDR channel counts under fixed baseline exposure. These numerical targets
are not a calibrated perceptual metric or proof of complete visual acceptance.

The enabled cache also executes in `qa-cloud-interior-bob-3`: 601 camera frames
with real bobView, 24 adjacent readbacks and valid history lineage. This is an
execution/motion-contract check, not an off/on temporal appearance comparison.
The separate radial defect was reproduced and corrected in the distant-world
operator; see CLOUD_INTERIOR.md. It was not fixed by the lighting lookup cache.

## Rejected next shortcut

`evidence/cloud-empty-space-feasibility-1.json` studies an exact conservative
coarse-shape bound from the original noise asset. It finds only 0.3% provably
empty 16m blocks and no empty 32m blocks when weather/height are ignored. A
hierarchy using that bound is not implemented: this evidence does not support
the hoped-for broad empty-space acceleration.

## Remaining limits

Nested lighting integration and the refined view-density march remain expensive.
Broader cloud-interior/approach appearance, complete user-motion A/B, 32-chunk geometry management,
finished sky/horizon/canopy/material/water appearance and release/rebuild/license
requirements remain open. The isolated environment color comparison does not
establish all viewing angles, weather, time transitions or other GPUs.
