# Entry-boundary leaf experiment

## Question and convention

The ordinary forest still has dark canopy/ground despite preserve-tint foliage
and eight diffuse vertices. The retained topology study establishes inward and
outward faces; it does not by itself establish a double-attenuation bug. This
experiment compares another explicit proxy convention: a leaf inward boundary
has the existing two-lobe material, while outward leaf boundaries are skipped
by radiance rays. Primary raster faces, material coefficients, solar shadow
entry counting, exposure algorithm and path/sample counts are unchanged.

`foliageTraceRadiance` lives in the example pack, shared by indirect,
reflection and water radiance queries. Mode 0 delegates to the original helper.
Mode 1 exposes all triangle candidates with NoOpaque, rejects outward leaf
faces by source geometric normal, then retains ordinary opaque/alpha policies.
Non-leaf geometry remains opaque, including its back faces. This is an artistic
thin-boundary representation of the closed voxel mesh, not calibrated leaf
thickness, a solved medium, or proof that the old two-sided convention is wrong.
Opaque leaf handling is implemented but the source fixture uses cutout oak.

The candidate is **not promoted**. A normal generated pack does not expose the
option. Explicit `-LeafBoundary 1` or `-LeafBoundaryComparison` includes
`LEAF_RADIANCE_BOUNDARY`; the comparison begins at 0 and performs 0/1/0.
Other existing installed packs and the user instance are not rewritten.

## Natural-scene comparison

`qa-leaf-boundary-comparison-1` passes six captures, day and sunset, runtime
8902D9B85DEC3CDFF67BAC76D52BB2DD397411B580DB603CB098245513B7CB8C.
The scene uses 8 chunks, the ordinary forest/river location, camera pitch 15,
8 diffuse vertices and 4 paths/lobe, preserve-tint optics, clouds/refined density,
the cloud lighting volume, depth-aware cloud composition, water/reflections,
native DLSS, exposure and display mode 2. No elevated reference tree is added.
Actual eye/forward, inverse view and geometry match in all six captures:
aef070acf8b100ae734344f34a66556dcc1072b912c713158884c579e60ad4ee.
Jitter/seeds and sun-category membership differ and remain recorded.

| Unexposed group mean vs bracketing original mean | Day | Sunset |
|---|---:|---:|
| Leaves without primary-sun radiance | approximately +14% | approximately +35% |
| Ordinary diffuse without primary-sun radiance | approximately −1% | approximately +6% |

The labels are radiance categories, not a proof of geometric solar occlusion.
These are group means, not converged or matched-pixel error bounds. Day exposure
changes slightly; sunset exposure stays at the +5 EV cap. Actual native
screenshots and separately labeled common-exposure pre-water/pre-SR CPU renders
were inspected. Leaf transmission changes visibly at sunset, but dense-canopy
ground contrast remains poor. The boundary convention alone is not the needed
visual solution. No finished material/preset or performance acceptance follows.

`leaf-boundary-analysis-1` verifies identities/options and hashes 594 capture
files. `leaf-boundary-summary-1.json` records group changes and wall cadence.
`leaf-boundary-fixed-exposure-1` contains diagnostic images, not reconstructed
game screenshots. Whole native screenshots remain in the terminal case archive.

## Source and lifecycle check

`qa-leaf-boundary-source-1` passes on
4030D276A6E730F46E52550089956B272895E1887192B251B87C17870892BDE6.
The subsequent runtime changes only CPU diagnostics; external shader sources
retain the compared implementation. The CPU reference independently uses the
Moller determinant sign to reject outward leaf faces for radiance queries;
primary raster and solar transmission retain their separate policies.

Before fixture removal, 385 front-lobe and 1,463 back-lobe first rays differ
from the original boundary convention. Source records include actual GPU
distance witnesses: for one back ray, the original .119 m leaf exit is skipped
and CPU/GPU both reach the non-leaf surface around 7.465 m. Front/back raw
checks validate 4,766 / 2,144 four-path aggregates (19,064 / 8,576 paths), including finite
depth-eight source paths. Source texture sampling, material response, water,
atmosphere, reload, resize and retirement pass in the ordinary synthetic run.
The separate 64-phase grazing-water failure remains open; this test does not
replace that sweep or establish moving-camera image quality for the new mode.

## Audit correction

NaturalLightingAudit previously hardcoded clear `atmosphere.lighting` for its
aerial composition reference. Cloud preview binds `cloud.lighting` to the
direct pass. The audit now captures the pass's actual AtmosphereLighting
binding and records that resource name. All six new captures validate their
component sum against this actual input. Old captures are preserved, not
retroactively relabeled. This is a diagnostic correction, not a lighting boost.

## Next visual work

Do not keep increasing diffuse depth or leaf survival as a substitute for the
desired forest image. The current experiment narrows the cause: exit-boundary
scattering affects leaf appearance but is not the main control over dark
ordinary surfaces. Review finite-sun shadowing, directional sky transport and
representative under-canopy/interior views with actual component captures.
No new DLSS feature is part of this step. Broader cinematic and release work
remains open.
