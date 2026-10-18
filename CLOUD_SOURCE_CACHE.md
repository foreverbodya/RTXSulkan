# Experimental cloud source-light cache

Motivation: the rough-specular sunset capture spends 82.76 ms in cloud.view,
79.57 ms in cloud.environment and 42.39 ms in cloud.composite. Each occupied
view interval repeats up to 512 solar density samples plus two ambient columns.
This blocks judging the image in motion, before broader optimization work.

`-CloudSourceCache` adds six 129 x 33 x 129 RGBA16_FLOAT volumes (26,359,344
bytes per generation, 52,718,688 bytes with the current graph history pairs).
They store the three solar-order transmissions and upward/downward hemisphere
transmissions, evaluated using the original directional integrator at grid vertices. Horizontal spacings
are 4, 16 and 64 m, vertical spacing 4 m. Centers snap to each world lattice;
they do not follow camera bob. The cache rebuilds for current cloud phase and
sun each frame. No stale history is reused.

Shading interpolates transmissions, then applies the existing scattering-order
weights and ambient irradiance. The outer eight cells blend
into the parent; the final border blends into the original integral. Unsupported
points retain the original calculation. Cloud view extinction, density detail,
opacity, proxy, geometry clipping, world shadows and native DLSS are unchanged.

This is a spatial approximation of source light. Coarse grids can blur cloud
shadow structure or alter brightness; matching density/opacity alone does not
establish image quality. `-CloudSourceComparison` uses the existing frozen
day/sunset 0/1/0 capture procedure, not a new acceptance framework. Compare actual
HDR cloud.environment (same directions, independent of projection jitter),
full native images, and total cache + consumer GPU cost before promotion.

## Retained first attempt

`qa-cloud-source-cache-comparison-1` passed its existing execution gates. The
first implementation interpolated optical depth in three RGBA32_FLOAT grids.
Source generation plus the three cloud consumers cost 13.13 ms day / 25.10 ms
sunset, versus 39.66 / 187.73 ms control. Wall cadence was 130.87 -> 102.82 ->
129.62 ms day and 297.04 -> 133.84 -> 309.12 ms sunset. Same-world geometry,
actual camera and unrelated options matched; do not compare these numbers to
other cases as matched scenes. Other runtime mod hashes remained unchanged.

However, cloudy fixed-direction environment RGB has relative L1 differences of
13.41% day / 11.61% sunset against the original GPU nested integration. The
repeated control is byte-identical. This approximation is **not accepted**.
Actual screenshots were inspected; arithmetic PASS does not erase this loss.
Interpolating optical depth before exponentiation darkens unresolved mixtures
(exp is convex); the new version caches transmissions themselves. It still
requires comparison and is not guaranteed to remove coarse-grid error.

Day environment alpha is bit-identical for all 32,768 pixels; sunset differs
in 925 pixels by at most one half-format step (0.00048828125). Density/opacity
source code was not intentionally changed, but GPU floating-point code generation
does not give bit-exact extinction invariance across the two shader variants.
Do not claim byte-exact opacity preservation. The original v1 shader sources,
run, failed quality decision and analysis are retained in evidence.

## Retained transmission attempt and finer coverage

`qa-cloud-source-cache-comparison-2` also passed execution gates but its coarse
transmission interpolation is **not accepted**. Cloudy environment RGB relative
L1 is 18.23% day / 19.24% sunset. The worst directional group is near the horizon:
35.89% / 44.24% for cosine elevation below .05. The original repeated GPU
reference remains byte-identical. Costs remain about 13 / 25 ms for cloud passes,
but faster output with this light smearing is insufficient.

The third candidate reduces spacings from 16/64/256 to 4/16/64 m, with unchanged
grid counts and memory. Its complete cache radius is 4,096 m rather than 16,384 m;
beyond that it uses the original nested integration. This trades some saved work
for denser source detail and an exact distant fallback.

`qa-cloud-source-cache-comparison-3` completed all six captures. Day wall cadence
is 132.62 -> 126.15 -> 131.15 ms; sunset 286.58 -> 265.47 -> 293.85 ms. Combined
cloud cost is 34.98 ms day / 164.88 ms sunset with cache, versus 39.51 / 187.36 ms
control. Distant fallback gives back most of the environment pass saving.
Cloudy environment RGB L1 remains 11.60% / 12.26%; the horizon group improves
to 8.20% / 8.86%, but the next elevation group remains 19.59% / 21.11%.
Repeated controls are again byte-identical. Actual day/sunset native comparisons
were inspected: global form is similar, source shading changes, and the scene's
flat canopy / dark ground are not improved. Extinction differs by at most one
half-format step, as in prior variants.

**Decision:** retain this explicit experiment for future work; do not promote or
install it. Its accuracy/cost compromise is insufficient to make normal motion
practical, and tuning more grid parameters is not the next visual milestone.
`evidence/cloud-source-cache-analysis-{1,2,3}` preserves the actual display,
fixed-direction HDR differences and total costs. Geometry is matched within each
run, not between the three independently loaded worlds. No motion/beauty claim.
