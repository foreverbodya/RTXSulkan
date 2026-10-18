# Traced rough dielectric reflection prototype

`-RoughReflections` adds a separate `rough.reflection` ray-query pass after the
existing smooth-metal composite. Four samples per supported pixel use the same
terrain BLAS/TLAS, atlas, alpha traversal and camera-relative origin contract.
`rough.composite` adds this contribution before water, clouds, native NGX
reconstruction and display. `ROUGH_SPECULAR=0` clears the new contribution.
`-RoughReflectionComparison` exposes the 0/1 switch in a frozen natural capture.

## Sampling and weighting

Implementation follows the geometric derivation in
[Heitz, Sampling the GGX Distribution of Visible Normals (2018)](https://jcgt.org/published/0007/04/01/).
The shader was authored from those equations. The proposal is isotropic GGX
VNDF with alpha = perceptual roughness squared. Its reflected-direction density
is D(h) G1_exact(v) / (4 n.v). The target retains the existing solar BRDF's
Schlick-GGX masking Gk(v) Gk(l). Consequently the sample weight is
F(v.h) Gk(v) Gk(l) / G1_exact(v). The NDF and Jacobian cancel. This is explicitly
an exact-Smith proposal for the existing approximate-masking target, not a change
to the project's complete BSDF model. Below-surface samples contribute zero;
there is no resampling or radiance clamp. Four samples form an arithmetic mean.

Primary nonmetal terrain and leaves are supported; water and small vegetation
classes 2..4 are excluded. Existing metal reflection behavior is unchanged.
The first outgoing event is specular. At a terrain hit the shared secondary
lighting supplies direct solar/emission, including leaf transmission/shadows;
it does not trace a diffuse continuation from that hit. Misses use the existing
sky/cloud radiance cache without a solar disk, avoiding overlap with explicit
primary solar lighting. Segment aerial light is included once; the camera segment
applies extinction only, since the direct pass already added its in-scatter.

This remains bounded one-bounce specular transport with approximate cached
environment, class materials and a 128 m scene query. Dynamic entities,
translucent reflected geometry, full specular/diffuse path continuation, PBR maps,
a dedicated reflection denoiser and specular motion guides are not implemented.
Native DLSS is retained; this does not add a new DLSS feature.

## Verification and current status

`rough-specular-cpu-integration-1.json` compares the double sampler/weight model
with independent solid-angle quadrature in twelve roughness/view cases.
Each uses 262,144 samples. Maximum integral difference is 3.4071e-7, maximum
normal-length error 7.78e-16; weights are finite and below one in these cases.
This is not a GPU FP32 sampling readback or proof of all BSDF energy properties.

The opt-in natural capture saves raw reflection, first-ray hit, pre-composite
HDR, resulting composite, frame and actual options before checks. The execution
gate checks finiteness, active support, first-ray sentinels/range, an exact
disabled fallback and legal adjacent-half composition stores. It does not
independently reconstruct source intersections, GGX GPU arithmetic or complete
transport. Numerical execution does not establish aesthetic/motion acceptance.

The six-capture run `qa-rough-specular-comparison-1` completed successfully with
runtime `28461741BA6B4D96304E45AE60B67EAC74F7827620398773548538D274120BF1`.
All six exact-off/finite/composition gates passed. Frozen actual eye, forward,
inverse view, geometry and unrelated options match within this run; source
geometry SHA256 is `a8fe0658ecf1bec283983fe3dec2b5eeff353a77e048c66fe7fe77ad16a6e8af`.
See `evidence/rough-specular-analysis-1` for the actual display comparison and
separate raw-term/cost report. The generic lighting groups precede this new pass
and must not be presented as a measurement of its added term.

The new pass costs 5.48 ms day / 6.66 ms sunset on this GPU. Wall cadence is
140.98 / 147.41 / 142.28 ms for day 0/1/0, and 307.43 / 326.39 / 309.69 ms at
sunset. These runs are far too slow for judging normal gameplay motion.
Day has 1,173,764 positive reflection pixels, sunset 911,216. Actual native
screenshots were inspected: foliage acquires cool environment glints, but the
broad flat canopy and insufficient ground light remain. This is useful transport
work, not a finished look. No user-instance update or preset promotion was made.
