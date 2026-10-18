# Experimental foliage absorption

The original leaf model is uncalibrated. Its RGB coefficients, for linear
texture/biome albedo `a` in [0,1], are `t = sqrt(a)/2`, `r = a*(1-t)`.
These coefficients are applied once per visited surface. Direct, secondary,
reflection and water shading use the shared foliage helper. Leaf shadows count
accepted inward crossings; diffuse paths interact with two-sided surfaces.
Changing absorption does not resolve the closed-voxel/surface interpretation.

## Optional material variants

`FOLIAGE_OPTICS` defaults to **0**, preserving the original coefficients.

* **1, spectral survival:** remap each channel's `S = r+t` to `sqrt(S)` and
  preserve its reflection/transmission fraction. This boosts the weakest
  channels disproportionately and can desaturate the material.
* **2, preserve tint:** find `M = max_channel(r+t)`. For M>0 multiply both
  complete RGB lobes by the same `1/sqrt(M)`. Thus the maximum surviving channel
  becomes `sqrt(M)`, each channel stays bounded by 1, and both lobe RGB ratios
  and reflection/transmission fractions are unchanged. Black stays black.

Both are artistic proxy materials. The square root halves a defined *effective
absorption depth* `-log(S)` (mode 1) or `-log(M)` (mode 2). It is not a measured
leaf thickness, a solved canopy medium, or an assertion of complete BSDF energy
conservation. Fresnel, ray selection, sky/solar brightness, exposure and display
curves are unchanged. Increasing survival can amplify low-sample indirect noise.

The shader uses inverse square roots; the independent double reference uses
log/exp and explicit RGB lobe fractions. The opt-in `foliage.optics` diagnostic
writes 24,576 actual FP32 RGB coefficients for 4,096 mixed-color inputs. Its
absolute tolerance is 0.000002. The diagnostic runs only with foliage/source or
natural lighting diagnostics, and its output is an explicit graph dependency.

Natural comparison mode writes component/HDR/frame/option inputs before checks.
Six trials use one frozen loaded world: original/candidate/original at day and
sunset. Source geometry equality, actual active option, HDR decomposition and
GPU coefficient arithmetic are required. Different jitter and stochastic ray
seeds remain; these are not perfectly paired high-sample radiance references.

## Retained first experiment

`qa-leaf-optics-comparison-1` failed during startup with an LWJGL source-string
stack exhaustion, before a valid visual comparison. See NATIVE_SHADER_COMPILATION.md.

`qa-leaf-optics-comparison-2` passes the six-trial mode-1 comparison on host
CD72D0FD2279AF9B9BD48A167EE3D38361CD4C80ABBCF89A160F0AA55F3507B3.
All six coefficient captures replay independently. Maximum original/spectral
coefficient errors are 0.00000007777 / 0.00000010633. Five actual-buffer corruption
mutants are rejected and the untouched original still passes. Property checks
also cover 65,537 scalar inputs per model and invalid inputs.

Day sampled leaf radiance without primary sun increases from 0.003349 to
0.010368, with return baseline 0.003382. These are local unexposed group means;
group membership and stochastic samples differ, not pixel-perfect error bounds.
Actual day screenshots were inspected: canopy becomes brighter but also pale.
The spectral candidate is **not accepted** as the cinematic foliage preset.
Mode 2 is compared separately below; a passing coefficient test is not visual
acceptance. Existing default and the user instance remain unchanged.

Source-triangle/PNG transport validation and combined motion have separate
executed evidence below. Representative interiors, longer histories, deeper
transport and a high-sample reference remain open requirements. No new DLSS
feature or optimization is part of this work.

## Preserve-tint candidate: executed evidence

`qa-leaf-optics-comparison-3` passes all six 0/2/0 trials on host
33779563E1ACEE152E3C20341B24A38BD5C76288DFA6B50762E7A7623A0420F1.
All six actual inverse-view matrices, camera eyes/forwards and source geometry
match exactly. Geometry hash is
042ad07590c5785629ab227cb35c8f7df8564c79406246dd08524febfaa8d44d.
Projection jitter/ray seeds differ and remain recorded. Actual time/weather/sun
inputs and direct-solar RGB match within each phase. Ambient output differences
are retained, up to 0.000000461936; no blanket environment byte equality is claimed.

| Group without primary sun: unexposed local luminance | Original | Mode 2 | Original return |
| --- | ---: | ---: | ---: |
| Day leaves | 0.003074 | 0.008086 | 0.003084 |
| Day ordinary diffuse | 0.003805 | 0.004837 | 0.003803 |
| Sunset leaves | 0.000788 | 0.001616 | 0.000791 |
| Sunset ordinary diffuse | 0.000803 | 0.000841 | 0.000802 |

These are sampled group means, not matched-pixel convergence errors. Day actual
exposure changes from +0.8820 to +0.3054 EV; sunset from +5 to +4.4909 EV. Brighter
foliage changes the meter, so brighter local transport does not imply every final
shadow is brighter. Actual screenshots and separate fixed-phase-exposure
pre-water/pre-SR diagnostic images were inspected. Mode 2 retains greener leaf
color, but dense canopy/ground contrast and distant terrain remain unfinished.
It remains an optional candidate, with **cinematic acceptance false**.

Six GPU coefficient captures independently replay within 0.00000010633 absolute
error; five actual-buffer mutants reject and the original still passes. Property
checks additionally cover 65,537 mixed RGB inputs and lobe-hue/energy bounds.
`leaf-optics-neutral-analysis-1` retains actual screenshots/contact sheet,
cadence/options and 114 input file hashes. `leaf-optics-neutral-fixed-exposure-1`
contains clearly labeled numerical diagnostic renders, not gameplay screenshots.

The initial offline analyzer compared the entire camera dictionary, including
the intentionally differing projection-jitter angle. Its failed output and
diagnosis remain in leaf-optics-spectral-analysis-1. The corrected analyzer
requires exact eye/forward and inverse-view equality and records jitter
separately. No renderer tolerance was changed.

`qa-leaf-optics-source-1` passes the mode-2 synthetic source reference and ordinary
lifecycle/camera scenario: 2,909 shadow rays, 1,329 leaf HDR receiver checks,
9,176 front and 1,028 back raw source-validated paths, independent bounded second
bounces, 11,860 water samples, scene removal, reload, resize and retirement.
Shadow/HDR maximum errors are 0.00000008117 / 0.000243571. Other mod hashes
are unchanged. This does **not** pass the separate 64-phase grazing-water sweep;
its recorded 0.0040064 m counterexample remains open at the 0.003 m limit.

`qa-leaf-optics-cloud-motion-1` also passes with mode 2, refined cloud density,
finite cloud guides and native SR. It records 601 path frames and 24 consecutive
readbacks with all 23 history/matrix relationships valid. See
leaf-optics-cloud-motion-analysis-1. This is scoped combined execution and
arithmetic evidence, not perceptual temporal acceptance or a performance test.
