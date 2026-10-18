# Experimental deferred ray lighting

This is the first direct-lighting implementation, not a finished cinematic pack.
Use `tools/Create-RayLightingPack.ps1 -Destination
'C:/path/to/minecraft/shaders/RayLighting'`, select RayLighting and launch with
`-Dsulkan.rayTracing=true`. For guarded validation use the world QA runner with
`-RayLighting`.

Solid and cutout terrain write exact depth identification, tinted linear color,
world normal, roughness, metalness and emission to separate attachments. Vertex
color still includes Sodium's baked shading/AO. Metal/roughness/emission values
are initial material-class presets; resource-pack PBR maps remain future work.

The compute pass evaluates a diffuse plus GGX direct BRDF. A hardware ray query
controls sunlight visibility against opaque and alpha-tested terrain. Cutout
candidates use the shared `sulkan/ray-terrain.glsl` helper and explicit atlas LOD 0.
The result stays in
RGBA16_FLOAT HDR until a separate tone-map and sRGB presentation pass. Sky fill
and block light are approximate; there is no traced indirect illumination yet.
Other geometry and translucent terrain retain their fallback rendering.

## Optional smooth-metal reflections

Add `-Reflections` to `Create-RayLightingPack.ps1` to enable the separate
`reflections.comp` and HDR composite passes. This gives the iron material class
an explicit smooth-metal preset and traces one reflection bounce through the
existing terrain BLAS/TLAS, including alpha-tested cutout candidates. Hit shading uses the live atlas, vertex tint, light
and material class; misses use a temporary analytic sky. It does not sample the
screen color. The ordinary direct-lighting pack remains available without the
switch. The reflection path currently includes static opaque/cutout terrain and
ideal smooth-metal reflections, not rough surfaces, entities or transmission.
Wind deformation is not yet reflected in the BLAS geometry.

`-DlssReconstruction` combines the reflected HDR with the existing NGX SR path
before full-resolution tone mapping. This is Super Resolution, not Ray
Reconstruction. Reflected motion and temporal reflection denoising are not yet
implemented, so the integration does not establish temporal visual quality.

For a disposable real-game check, use `Run-QaWorld.ps1 -RayLighting -Reflections`
with its required paths. The reflection fixture is separate from the quartz
direct-lighting oracle: it adds an iron floor and an emissive ceiling. The
reflection oracle analytically intersects rays with the known ceiling plane,
checks the whole emitter is outside one camera frustum plane, verifies HDR
composition and checks that removing the ceiling removes its ray hits. Run the
ordinary `-RayLighting` mode separately to retain direct-lighting regression
coverage. Add `-WorldDlss` and the external native/runtime paths to exercise
Quality reconstruction, camera movement, jitter and resize together.

The QA diagnostic asynchronously copies actual attachments back from the GPU.
It checks HDR finiteness and emission above display range, unit surface normals,
and a quartz-only fixture patch against separate CPU slab shadow intersections
and double-precision BRDF evaluation. Diagnostic copies require opt-in texture
copy permissions and do not run in normal gameplay. A successful test is scoped
to this fixture; it does not prove all materials or scenes correct.

The optional `-AlphaDiagnostic` generator / `-AlphaTraversal` world QA mode compares
ray hits against a separate CPU triangle/source-alpha oracle for stationary oak.
See `ALPHA_TRAVERSAL.md` for exact coverage, backface semantics and retained failures.

Still required: shadow-specific alpha oracle, entity and wind geometry, rough reflection and
indirect lighting, temporal denoising, transparent materials, complete fog and
dimension integration, camera-relative precision, allocation pooling, motion
complete object/reflection motion vectors, DLSS Ray Reconstruction if selected,
and measured 1440p / approximately 100 FPS performance. Camera motion, projection
jitter and NGX Super Resolution are already integrated and tested in the
engineering fixture; they do not establish finished cinematic image quality.
