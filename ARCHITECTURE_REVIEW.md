# Architecture review — 2026-10-17

This is a scoped source review, not approval of the complete renderer or release.
The exact original 0.5.1 author source remains unavailable. Java was reconstructed
from the official binary; the Git baseline is older and its raw diff therefore
mixes upstream version changes with this project's work. Do not describe that
diff as entirely new code. See REUSE_AUDIT.md and the source checkpoints.

## Runtime map

| Responsibility | Main source | Ownership / boundary |
| --- | --- | --- |
| Lifecycle, pack selection and fallback | ShaderRuntime, NativePackRuntime | Prepare/activate graph; preserve accepted mesh encoding on compute option changes |
| Pass graph and resource lifetimes | PackGraph, GraphRenderer, GraphBuffers, ComputeProgram | Validate graph; own render targets, programs, scene and reconstruction generations |
| Accepted Sodium geometry | TerrainGeometryCache, TerrainMesh, geometry mixins | Copy accepted data; other mod binaries are unchanged |
| Hardware traversal | TerrainRayScene, AccelerationStructureBuilder, RayAttributeArena | Incremental BLAS/TLAS; consumers record on the engine encoder; old inputs retire after GPU readers |
| Native resources | NativeBuffer, AccelerationStructure | Explicit owners; close before submission or after engine completion |
| Reconstruction | DlssReconstruction, DlssSession, native/dlss/ngx_session.cpp | Size generations own outputs; registered native contexts own NGX parameters/features; calls serialized |
| Lighting and materials | examples/ray-lighting | GLSL transport and atmosphere; generator builds an explicit graph; no Minecraft QA scene coordinates in the lighting model |
| Optional diagnostics | diagnostics, graph/*Smoke | Source oracles, disposable world setup, readback and reports; enabled through JVM launch arguments |
| Offline tools | tools, tools/probes | Pack generation, launch, source analysis and replay; not game frame code |

## Changes made in this review

Previously SulkanShadersClient invoked six diagnostic tick handlers during every
normal client tick; each handler checked its flag and returned. DiagnosticHooks
now registers only explicitly requested handlers. The normal scene return hook
also goes through its startup QA gate. Existing diagnostic request flags and
callback order are retained. Startup requests are immutable for the process;
changing these system properties in a running game is not supported.

Diagnostic classes still ship in the current development JAR. This change
separates entry points; it does not claim a separately packaged QA artifact.
NativePackRuntime's environment-smoke observer retains its existing development
environment/request guard. Runtime readback APIs remain explicit diagnostic
accessors; normal graph execution does not request their CPU copies.

Original Minecraft texture samples are also present inside the new source
geometry JSON snapshots and second-bounce failure snapshots. These local replay
payloads are now explicitly ignored by Git, alongside raw images/buffers. They
remain on disk for investigation; they are not cleared for redistribution.

## Reviewed resource paths and remaining risks

- TerrainRayScene records builds without its own submit/device-idle wait and
  defers old TLAS/BLAS, staging and attribute-range release through queueForDestroy.
  Recorded-but-not-executed builds free their newly owned resources. Failure
  after encoder execution has fewer recovery guarantees; this is not proven by
  ordinary lifecycle tests. Do not add immediate destruction on that path.
- NativeBuffer rolls back partially allocated buffer/memory and unmaps in finally.
  Its public close requires the caller's GPU completion guarantee.
- DlssSession uses a renderer-thread recording check, native context IDs and a
  shared SDK under a mutex. Retirement closes NGX after the engine completion
  callback. Overlapping sessions and injected native failures remain unverified.
- GraphRenderer.close sets closed before sequential disposal. A disposal exception
  can prevent later owners from being visited. This failure-path risk is recorded;
  no exception injection or repair is claimed in this review.
- GraphRenderer and PackGraph each exceed 40 KB. They combine substantial duties;
  this is a maintenance concern, not sufficient evidence to rewrite them. Extract
  only when a concrete change needs a separable responsibility.
- One hard solar direction, approximate leaf optics, bounded diffuse transport
  and biased filtering remain image-quality limits. More diffuse depths alone
  did not resolve canopy contrast. Beauty is not established by numeric agreement
  between the shader model and its CPU implementation.

No architecture-wide cleanliness, leak freedom, full compatibility or release
readiness is claimed. Source provenance, licensing, grazing water, temporal image
quality and full user-modpack gameplay remain open gates.

## Verification of diagnostic entry-point separation

The modified host is
6D3132DF958A0CFB5246F2361BA05A5DD2A4D1C54C861A4CB11E94E24D11C836.
Its offline build and qa-diagnostic-hooks-1 synthetic game run pass, including
eight-depth raw references, ordinary camera/lifecycle, reload, resize and scene
retirement. Other mod hashes remain unchanged and master sound is zero. This
does not replace the separate grazing-water or natural-world motion checks.

Twenty CPU contract cases compile the actual DiagnosticHooks source against
minimal API doubles. They cover no request, each request type, explicit false
flags and each smoke request in ordinary/development environments, including the
frame gate. They establish registration decisions, not actual Fabric class-load
isolation or default gameplay. Script and report are retained in evidence as
diagnostic-hooks-contract-source-1.py.txt / diagnostic-hooks-contract-1.json.

Whitespace checking passes for the touched tracked entry-point/ignore files.
The wider pre-existing worktree still reports whitespace problems in other
reconstructed resources; no repository-wide formatting pass is claimed.

## Evidence from the preceding transport run

qa-canopy-eight-source-1 failed coverage (98 accepted back-lobe pixels after
removal; minimum 100). It remains archived as failed. The old fixed audit stride
was 11 pixels. qa-canopy-eight-source-2 explicitly uses stride 8 with unchanged
eligibility, minimum counts and numeric tolerances; it passes the ordinary
synthetic lifecycle on host 3A038E03BCF8A7549C38E8C2EFA05F9FC01CD71A94B2036F2AFD2C996B7FA36A.
The removed back lobe now checks 179 pixels / 716 complete four-path aggregates;
its first path reaches depth eight in three recorded cases. This is finite
source transport evidence, not a converged image or the separate water sweep.

Inputs are now saved before assertions: frame, named GPU buffers, actual options,
CPU triangles and original nonanimated block texture samples. No standalone
transport replay tool has yet been supplied for this snapshot.

Inspect-LeafBoundaries.py examines that actual source mesh with fixed geometric
rays, ignoring alpha and throughput. A one-block leaf wall has one inward and
one outward crossing on all 64 rays. The two-layer canopy has two inward/two
outward crossings on 55 rays and two/one on nine. This establishes surface
topology only. Straight shadow attenuation and two-sided diffuse scattering are
different approximations; these counts do not prove a double-attenuation bug.
