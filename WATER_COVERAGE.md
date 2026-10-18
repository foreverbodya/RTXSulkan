# Water raster/source coverage policy, version 1

The old strict center-ray requirement fails a saved raster-covered water pixel
at (1359,183). Its ideal source ray misses the closest triangle by a minimum
barycentric of -0.00000873067. The projected edge is 0.000620761 pixel away,
below one subpixel at the observed 8-bit precision. Vertex-nearest rounding
covering the pixel is diagnostic evidence, not a claim about GPU rounding.

This revision **changes coverage acceptance**. Position (0.003 m), normal
(0.001) and motion tolerances remain unchanged. Historical failed runs stay
failed; their outputs are not rewritten or relabeled.

`RasterCoverageReference` adapts the single-sample uncertainty model in
[Khronos CTS calculateTriangleCoverage](https://github.com/KhronosGroup/VK-GL-CTS/blob/bee83722547054754049485ae6ae8344496c02a6/framework/common/tcuRasterizationVerifier.cpp).
The current Vulkan physical-device `subPixelPrecisionBits` is queried. Unsupported
precision is rejected rather than clamped. Floor/ceil vertex positions and the
central square of three subpixels distinguish FULL/PARTIAL/NONE. This is a
bounded interval classifier, not exact GPU raster emulation or the complete CTS.
The adapted code retains its Apache-2.0 license and attribution in JAR resources.

Ordinary strict hits keep the previous source-boundary/normal-footprint checks.
A strict miss can proceed only with a positive, front-facing source-plane
intersection, a PARTIAL coverage interval, matching original raster position
and normal budgets, and an unclipped positive-W source triangle. NONE, FULL
without a strict hit, missing geometry and nonfinite values still fail. The
source-plane candidate then receives the original depth, validity and motion
checks. It is counted as `coverageUncertainPlaneSamples`, never as a strict hit
or silently skipped. At least 100 strict interiors are independently required.
Before numerical assertions, a JSONL witness preserves source triangle, frame,
GPU depth/normal/validity, motion half bits and finite decoded values.

`water-coverage-legacy-replay.json` retains the old case as a strict miss and
finds a PARTIAL candidate: position error 0.0000109997 m, normal error
0.0000451161. Four negative cases reject wrong-plane, reversed normal, empty
geometry and NaN position. There are 56 classifier cases across both windings
and 4/8/12/16-bit precision. Legacy motion inputs were not recorded and this
offline replay does not validate motion.

`qa-water-coverage-1` passes on D954887E…CEA8E4F3. Its water-motion phases contain
12,716 to 13,348 strict interiors each and **zero uncertain-plane samples**.
Thus it exercises existing motion checks with the new host, but does not prove
the new uncertain-edge path on a live GPU. A deterministic case exercising that
path, including recorded motion, remains required. No visual change or fix to
water rendering is claimed from a diagnostic-policy correction.

## Latest full baseline regression

qa-cloud-column-regression-1 fails on A6D08D0E...A5DE6C02, with cloud preview
disabled, at water source/raster pixel 149763: position difference
0.004006400935881841 m exceeds the unchanged 0.003 m limit. All seven buffers,
frame and exact 19,208 source triangles are retained in its failure capture.
This is a separate recorded counterexample, not a repair of earlier cases.
The full sweep and later world lifecycle gates remain incomplete. See
cloud-column-regression-status-1.json. New foliage/compiler work does not waive it.
