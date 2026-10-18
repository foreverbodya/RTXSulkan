# Leaf surface experiment

`FOLIAGE_SURFACE=1` changes the leaf dielectric roughness from .72 to .35.
`material-surface.glsl` shares this authored class value between the G-buffer
and secondary solar BRDF in diffuse paths and water shading. Other classes,
Fresnel F0, diffuse/throughput coefficients, transmission, geometry and solar
brightness stay unchanged. This is a roughness preset, not a measured PBR map,
new coating or complete glossy transport. Smooth-metal reflection hits retain
their historical approximate Lambertian leaf illumination.

The option is exposed only by explicit `-LeafSurface 1` or
`-LeafSurfaceComparison`; default zero retains the old value.

## Evidence and decision

`qa-leaf-surface-comparison-1` fails during the sunset **control** capture on
`Cloud supported proxy mismatch 1409127`. Its complete day captures and failed
sunset inputs are retained. It is not a complete passing comparison.

After the cloud precision correction below, `qa-leaf-surface-comparison-2`
completes all six frozen day/sunset 0/1/0 captures on runtime
2B5873F49937E0D0BC43AF0079047C9C694A0D64D0928DE054C9C61A47F3C17F.
`leaf-surface-analysis-1` verifies identical geometry, actual camera and view
within the run and unchanged unrelated options, hashing 348 files. Geometry:
8daad7a270a6aaca1edddd838fe1b062e38709e1760a460e4d450e82fcc1e2bc.
Jitter/path seeds differ; this is not a converged matched-pixel reference.

Actual native day/sunset pairs and separate common-exposure pre-water/pre-SR
HDR views were inspected. Broad highlights change and some faces darken, but
material richness/canopy depth remain insufficient. **Not promoted.** The CPU
material reference and build are updated; a broad source-transport run with
this material selected has not executed. Motion and aesthetic acceptance stay open.

The inspected reflection pass excludes nonmetals and roughness above .025.
Changing leaf roughness alone cannot supply missing traced environment response.
Next implement bounded rough specular terrain reflection using the existing ray
scene, preserving mirror behavior and explicit solar accounting. Assess appearance,
variance and motion. [Heitz's GGX VNDF paper](https://jcgt.org/published/0007/04/01/)
and [Filament's material notes](https://google.github.io/filament/main/filament.html)
were consulted for that future step; no implementation from them was copied or
integrated here.

## Cloud footprint correction

At failed pixel (852,825), exact weight .0002196836555 became .0002196729183
because `1-f` cancelled after rounding f near one. Opacity is only 2.0872e-7:
GPU proxy 136.22653198 m versus independent double 136.23221970 m; old FP32
emulation predicts 136.22651672 m. The shader now divides the integer remainder
and its integer complement independently. Analytic weights are unchanged.
The retained-input prediction becomes 136.23222351 m (error .00000381 m).

`cloud-supported-proxy-witness-3.json` records the inputs and limits, correcting
v2's accidentally FP32 error subtraction; v2 remains explicitly superseded.
The prediction is not an exact old-input GPU replay. Six new captures check all
1,638,720 pixels each with the unchanged double reference and error budgets.
Offline replay of the old capture still fails at that pixel; new sunset control
replays successfully. No tolerance or source classification changed. This specific
arithmetic fix does not establish complete cloud-motion/user-route correctness.

No user-instance or other-mod update. Solar Tmin, grazing-water and night-gate
failures, playable speed, interior/motion quality and release provenance stay open.
