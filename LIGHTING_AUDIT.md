# Natural lighting component audit

Status: opt-in diagnostics measure the actual forest's material and lighting
components. The audit is not a new lighting model or independent path oracle.

## Capture contract

`-LightingAudit` requires the natural world, atmosphere, static leaf indirect,
spatial filtering and auto exposure. The direct-light shader additionally writes
five FP32 images: primary solar radiance/material label, reflected diffuse,
transmitted diffuse, local emission, and linear albedo/source depth. These are
the actual terms immediately before camera aerial perspective. Nonterrain pixels
have a zero label. The normal render output is unchanged.

Only the opt-in audit allocates these images, pins them through end-frame
readback, and uses the explicit 2048 MiB diagnostic graph budget. Scene targets
remain non-history resources; the audit copies its own source depth. Readbacks
start after cadence measurement. Radiance is measured before exposure/display.

The disposable server and actual camera are frozen. Source geometry is hashed
and must match at both day and sunset. Measurement requires at least 20 seconds
and 100 frame intervals; expensive quality configurations extend the interval
rather than impose a minimum FPS. Actual duration is reported. This is wall
cadence including QA camera guards, not GPU pass timing or performance acceptance.

Every audit image is checked for finite components. A regular grid with stride
5 is summarized by material and presence of primary-sun radiance. The sum of
captured radiance terms, after the existing double aerial reference, must match
the actual pre-water HDR target within the existing `0.005+0.007*abs(expected)`
composition tolerance. This verifies the decomposition's arithmetic and labels;
the underlying light transport retains its separate source-based checks.

## Executed audit

`qa-natural-lighting-audit-2` passes on host SHA256
`0B1DC4BC0876FC3FBE2EF743D2007E8E91A92A40C955348CA98B7FD21A02B4DE`.
Configuration: 16 raw paths per pixel per lobe, eight bounded diffuse depths,
static leaves, spatial/temporal filtering, atmospheric multiple scattering,
water, AgX display and existing 1440p Quality reconstruction. This combined
quality configuration is not claimed as independently source-validated or
converged by this audit alone. Other mod hashes remain unchanged.

Both phases retain geometry SHA256
`39e97174571ed28d667197a3f3445b336cc34378df12f67e12a45e224dfe3563`.
The sampled grid covers 27,106/27,136 leaf and 20,108/20,118 other material
pixels at day/sunset. Maximum HDR composition error is 0.000485404/0.000029963.

| Sampled group without primary-sun radiance | Day mean local luminance | Sunset mean local luminance | Fraction below 0.001, day / sunset |
| --- | ---: | ---: | --- |
| Leaves | 0.003639 | 0.000897 | 8.17% / 63.33% |
| Material 0 (ordinary diffuse preset) | 0.004178 | 0.000913 | 19.96% / 60.98% |

These are means of unexposed radiance, not perceptual brightness or median output
pixels. The group label means solar radiance <=1e-6; it does not distinguish
geometric occlusion from an unlit orientation. All sampled receivers in these
groups have supported traced diffuse input. Day exposure is +0.945441 EV;
sunset reaches the existing +5 EV ceiling. Day shadowed-leaf mean linear green
albedo is 0.115740, rho 0.094607 and tau 0.165861. These are measured inputs to
an uncalibrated preset, not measured physical leaf properties.

The data establish nonzero indirect signal in many visibly dark surfaces. They
do not uniquely identify insufficient transport, leaf optics or display contrast
as the cause. Next compare a documented photographic shadow grade on exactly
the same HDR/exposure frame, while leaving illumination unchanged.

## Retained setup failure

`qa-natural-lighting-audit-1` is a failure. The first diagnostic generator marked
the scene target `surface.data` as history, violating the existing graph rule;
the pack was rejected and the loader fell back to builtin. No audit results from
that run are accepted. The generator now writes audit depth to its own output,
and natural QA rejects an unexpected active pack before creating its test world.
The graph validation rule was preserved.

## Material comparison extension

Subsequently the requested audit retains raw inputs for every capture, not only
leaf comparisons. Reconstruction depth and the actual atmosphere sky are added
for horizon forensics; those diagnostic graph resources are pinned explicitly.
The shared frozen-lighting analyzer serves material, bounce and horizon trials.
The original leaf CLI remains a compatibility entry point; its numeric results
and input hash manifest replay identically. This does not relax the earlier
performance analyzer or establish convergence/throughput.

With LeafOpticsComparison, each of the six captures saves raw component targets,
actual pre-water HDR, frame and active options before assertions. The shared
foliage coefficient diagnostic has an independent FP32/double check; group
rho/tau summaries use the actual captured material mode. Capture names include
the option/trial, so reports do not overwrite earlier trials. The ordinary
lighting-audit allocation remains explicitly diagnostic. See LEAF_OPTICS.md
and Analyze-LeafOptics.py for matched source geometry/view, differing jitter,
actual exposure, retained failures and limits of the numerical/visual evidence.
