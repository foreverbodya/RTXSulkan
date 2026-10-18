# View-density sampling study

The detail field contains nominal 16/8/4 m erosion frequencies. The current
view integrator evaluates one density midpoint per lighting interval: target
at most 6 m, capped at 256 intervals across up to 8000 m. Near-horizontal cloud
segments therefore have steps larger than the density detail. This affects
opacity, the apparent volume boundary and its representative depth before
temporal reconstruction.

`StudyCloudViewQuadrature` replays actual captured half-resolution sky rays
from `qa-cloud-adjacent-guides-1` day. Original layout/noise PNG hashes and the
captured density model/phase/bottom are checked. Eight rays each are selected
deterministically from clear, partial and opaque strata. It independently
integrates the existing <=256 schedule and uniform midpoint grids targeting
4, 2, 1, 0.5, 0.25 and 0.125 m in double precision. Beer transmittance and the
opacity-weighted first distance moment are evaluated; no solar/ambient radiance
or aesthetically tuned comparison is substituted for density convergence.

Evidence: evidence/cloud-view-quadrature-study-1.json. On these 24 rays:

| Grid | Maximum transmission difference from 0.125 m | Maximum proxy-distance difference |
| --- | ---: | ---: |
| Current schedule | 0.01313556 | 34.17667 m |
| Target 4 m | 0.00952647 | 4.50293 m |
| Target 2 m | 0.00129224 | 1.67038 m |
| Target 1 m | 0.00051554 | 0.10388 m |
| Target 0.5 m | 0.00011549 | 0.03300 m |
| Target 0.25 m | 0.00001679 | 0.03571 m |

Proxy differences need not converge monotonically. The finest grid is not a
proved continuous-integral reference or rigorous error bound. Actual GPU versus
same-schedule double transmission differs by up to 0.00051408 (including half
storage and float-coordinate/density arithmetic); proxy difference is at most
0.15365 m. These differences are reported rather than hidden in a relaxed
conformance tolerance. The much larger sparse/dense discrepancy demonstrates
a sampling problem on actual rays, without proving every image pixel's error.

The largest proxy discrepancy occurs at view pixel (500,201), segment
2480.8066–5249.0330 m and current step 10.81338 m. Current double proxy is
4361.25225 m, finest proxy 4395.42892 m; actual GPU proxy is 4361.0986 m.
Current double transmission is 0.72355233, finest 0.73668789 and actual GPU
half transmission 0.72314453.

## Refined interval density — implemented experimentally

`CLOUD_VIEW_DENSITY_SUBSTEPS=1` integrates density more finely inside each
existing lighting interval. The default 0 retains the midpoint path. A target
1 m subgrid requires at most 32 density evaluations per current interval under
the 8000 m / 256 limit. Accumulate optical depth and its local opacity-weighted
distance moment. Use the resulting column for all existing scattering-order
weights, and evaluate the interval's tint, solar, ambient and air source at its
opacity centroid. This preserves a coherent color/opacity/proxy contract while
separating density and lighting quadrature rates.

This does not solve fine solar/ambient source variation: source radiance still
has a constant-per-lighting-interval approximation. A representative tint/source
at the opacity centroid can miss variation within the interval; this is not a
fully converged heterogeneous radiance integrator. No new DLSS feature or
performance target is introduced.

## Live checks and retained failures

`qa-cloud-column-comparison-1` failed before world creation: the diagnostic pass
incorrectly mixed 32x16 and 32x512 output extents. The existing graph contract
rejected it. `qa-cloud-column-comparison-2` reached the world but failed because
the diagnostic outputs were not reachable in the scheduled graph. Both failures
and their working directories/manifests remain. Two separate passes now preserve
equal output extents; opt-in diagnostic outputs use the existing completed-frame
readback dependency pattern. Neither graph checks nor numeric budgets were relaxed.

`qa-cloud-column-comparison-3` passes six frozen-source 0/1/0 day/sunset captures
on runtime A6D08D0E030A1D0C6C3D8200E9B1A8408119C8A826BC64102B0618A7A5DE6C02.
All six source geometry SHA256 values equal
ae8c4e1f9641354eb9c54a62147469c4d51ac2b9b7918bb513bae5296e7a592b.
The actual selected option is checked at each trial. Density, solar world-map,
receiver, scattering-order, reconstruction-input and composition gates pass.
The unchanged scattering-order slab test retains its previous numeric errors.
Other mod hashes remain unchanged and the disposable launches retain volume 0.

The new local-column check retains four GPU buffers, actual frame and original
PNG hashes before assertions. Each phase tests 512 intervals, 2880 actual source
positions/densities, 70 nonempty and 442 empty intervals, including zero and tiny
lengths. Maximum source-density error is 0.0000000452594, position difference
0.000225667 m, optical-column error 0.000000299115, opacity error 0.000000640078,
and local-centroid error 0.00000749815 m. These are implementation arithmetic
errors against the captured sample grid, not continuous-integral accuracy.
All six offline column replays pass. Six corrupted copies (column, centroid,
opacity, position, density and stale unused slot) are rejected; the unmodified
capture still passes. Column buffers are byte invariant across all trials.
World shadow maps are byte invariant within each day/sunset phase; per-frame
receiver/frame captures are not claimed invariant.

## Source-grid and image result

`StudyCloudViewQuadrature` now evaluates both schedules on each identical
captured ray before comparing with the dense grids. Historical study-1 remains
unchanged. Study-2 on its original 24-ray set gives maximum transmission
discrepancy 0.01313556 for midpoint versus 0.00046610 for refined density;
proxy discrepancy 34.17667 m versus 0.21130 m.

Candidate day/sunset each have an additional 24-ray source-grid study. On the
day set, same-ray original/refined transmission discrepancies are 0.03014363
and 0.00043685; proxy discrepancies are 30.66382 m and 0.21840 m. Actual GPU
versus finest-grid transmission differs by at most 0.00022529 (day) and
0.00067429 (sunset); actual proxy differs by at most 0.20611/0.30510 m.
The finest grid is still not proven ground truth. In the sunset original
schedule one thin cloud is missed entirely: proxy 0 is an empty sentinel versus
2166.82 m for the finest grid at about 0.0091 opacity. That raw large proxy
discrepancy is retained, not described as a stable surface-depth error.

Day and sunset baseline/candidate/return images were inspected. Visible change
is modest; clouds remain too soft, the terrain horizon unfinished, and canopy
shading dark. Raw cloud-luminance/opacity changes are smaller than baseline-return
jitter variation in this comparison, so this is not a demonstrated large visual
improvement. The independent same-ray density study supports improved sampling
precision; it does not establish full radiance or temporal quality.

Default stays 0 and the user instance is unchanged. The combined run
qa-cloud-column-motion-1 now passes with refined density and finite guides:
601 rendered path frames, 300 positive/300 negative translation and yaw frames,
and 24 consecutive readbacks with all 23 history/matrix relationships valid.
The archived capture hash list is verified; see cloud-column-motion-status-1.json
and cloud-column-motion-analysis-1. This establishes combined execution and scoped
arithmetic/history lineage, not temporal visual or cinematic acceptance.
Source/radiance convergence,
leaf lighting, materials/water, gameplay, exact original-source provenance,
licensing and release gates remain open. The separate grazing-water failure and
incomplete 64-phase motion sweep are not closed by these static captures.

The runtime JAR, sources JAR and native DLL reproduce byte for byte after a
local clean rebuild; independent pack generation reproduces all 104 files.
See cloud-column-clean-rebuild.json and cloud-column-pack-reproduction.json.
These are local checks, not independent-checkout/source-provenance or release
clearance. Raw image/GPU/checkpoint payloads stay on disk and are excluded from
Git; compact public validation artifact selection remains release work.
