# Development source snapshot

This repository snapshot records the current experimental work. It is not a
finished RTX preset, a binary release, or a declaration that the full project
goal has been achieved. See `IMAGE_QUALITY_PLAN.md` for the visual priorities.

## Source and license provenance

The Git history retains public upstream commit
`c588577a53dc70bc5d6ef3999ec7930ff6b6ff77` from
[mravatins/sulkanShaders](https://github.com/mravatins/sulkanShaders).
The newer Minecraft 26.3 baseline was reconstructed with Vineflower 1.12.0 from
the author's official Sulkan 0.5.1 artifact; these are **reconstructed sources,
not the author's original 0.5.1 source checkout**. `DEVELOPMENT.md` records the
artifact URL/hash and the distinction between that artifact and public 0.3.1.

The host retains the upstream GPL-3.0 license. Third-party shader notices are
retained under `examples/ray-lighting/LICENSES`. The bundled Sulkan Realistic
shader archive is a runtime resource from that official baseline, with provenance
documented in `DEVELOPMENT.md`; the own experimental shaders are under `examples`.
The original native adapter source has its own notice under `native/dlss`.
NVIDIA SDK headers, libraries and runtime DLLs are external prerequisites.
This upload does not resolve the remaining license/combined-binary distribution
review recorded in `REUSE_AUDIT.md` and `DLSS_INTEGRATION.md`.

## Build and run

Use Java 25 and the checked-in Gradle wrapper (`./gradlew build` or
`./gradlew.bat build` on Windows). Versions are pinned in `gradle.properties`.
The host source and generated source JAR have been built locally; a clean build
from the uploaded checkout on a separate environment is not yet established.

The native adapter is separate. `tools/Build-NgxProbe.ps1` requires a separately
obtained NVIDIA SDK and a C++ toolchain; see `native/dlss/SESSION.md` for runtime
requirements. The mod JAR does not contain the SDK/runtime. Generate the own pack
with `tools/Create-RayLightingPack.ps1`; its feature switches deliberately expose
unfinished experiments. Launch hardware RT with `-Dsulkan.rayTracing=true`.
This is not yet a one-click user distribution.

## Evidence included and retained locally

`VALIDATION_SNAPSHOT.json` is a compact inventory of actual recorded run outcomes,
artifact hashes and failures at publication time. It deliberately distinguishes
execution checks from visual acceptance. `docs/previews` contains selected real
GPU captures of the authored scene, not generated mockups.

The full `evidence/` tree, screenshots, raw GPU buffers, captured game texture
samples, logs, source checkpoint ZIPs, build caches and SDK binaries remain local
and are excluded from Git. Historical documents may refer to those local evidence
paths; their absence from this source upload is intentional. The source and probe
programs needed to perform new captures remain in the repository. No new game
run or test suite was executed solely for this upload.

## Still incomplete

Dynamic reflection/disocclusion quality, first-person items, broader sky/cloud/
water movement, representative outdoor frame cost and material presentation,
long travel at 32 chunks, independent source/PDF checks of emitter sampling,
retained grazing-water and indirect-solar precision failures, complete provenance/
license review and independent build reproduction. The previews do not close
these requirements.
