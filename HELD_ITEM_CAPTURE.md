# First-person item reproduction (2026-10-16)

Status: the reported server defect remains open. No renderer fix is claimed.
The user reported a compass hidden by opaque terrain but visible over water.
Their screenshots followed a switch to `__builtin__`.

## Source findings

- `GraphSceneCapture.captureWorldDepth` captures once per frame. The extra call
  from `GraphRenderer.render` does not overwrite a prior world-depth snapshot.
- Minecraft 26.3's locally inspected GameRenderer bytecode clears the hand
  target's depth to zero before `renderItemInHand`.
- Non-reconstructing packs run their graph after hand rendering and expose
  `$handDepth`; the builtin atmosphere shader has an early hand-depth branch.
- Reconstructing packs finish the world before `render3dHud`; vanilla hand and
  screen effects subsequently render at output resolution.

These observations rule out the specific proposed unconditional depth-overwrite
explanation. They do not prove the entire composition path correct.

## Capture fixture

`Run-QaWorld.ps1 -HeldItemCapture` enters only after the existing disposable
directory/world identity checks. It creates six screenshots: empty main hand
and an actual compass against stone, persistent oak leaves and water. Creative
flight/no-gravity fixes the pose; inventory and feet/yaw/pitch are asserted
before each capture. Integer teleport centering is avoided with explicit decimal
coordinates. The runner now requests 2560x1440 for this mode.

This is a **capture completion gate**, not automatic image-quality or visibility
conformance. It replaces the ordinary lifecycle/transport sequence for that run;
the result scope explicitly says those checks were not executed. It does not
modify the user's instance or saves.

Tested Java artifact:
`4F27FE43D9569F9318502CDC90E869926B0BCE0CEA2016134F7AF548812D2204`.

| Run | Conditions | Result |
| --- | --- | --- |
| `qa-held-item-builtin-1` | initial capture harness | rejected by camera guard; no visibility conclusion |
| `qa-held-item-builtin-2` | no gravity, integer teleport coordinates | rejected: actual X/Z=12.5 rather than 12.0; guard retained |
| `qa-held-item-builtin-3` | builtin, no forced jitter, 854x480 | six captures completed; compass visually present on all three backgrounds |
| `qa-held-item-builtin-jitter-1` | builtin, forced world jitter, 854x480 | six captures completed; compass visually present on all three backgrounds |
| `qa-held-item-builtin-1440-jitter-1` | builtin, forced world jitter, 2560x1440 | six captures completed; stone/water compass screenshots inspected, item present |
| `qa-held-item-rtx-1` | experimental reconstruction, leaf indirect/spatial, two bounces, exposure, static HDR water, 2560x1440 | six captures completed; stone/leaf/water compass screenshots inspected, item present |

The experimental run uses synthetic diagnostic variants, including water controls;
its appearance is not a product preview. Historical low-resolution runs are
retained instead of relabelled as 1440p runs. Other mod binaries remained unchanged.
Reports, logs and images are under the corresponding `evidence/` directories.

## Reproduction commands

From the workspace root, after building the current artifact:

```powershell
& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods `
  -QaDirectory ./work/qa-held-item-builtin-new `
  -HeldItemCapture -TemporalJitter -TimeoutSeconds 300

& ./outputs/sulkan-cinematic/tools/Run-QaWorld.ps1 `
  -InstanceMods ./work/qa-world-dlss-5/mods `
  -QaDirectory ./work/qa-held-item-rtx-new `
  -HeldItemCapture -RayLighting -Reflections -Atmosphere -WorldDlss `
  -AlphaTraversal -IndirectDiagnostic -LeafIndirect -IndirectSpatial `
  -IndirectBounces 2 -AutoExposure -WaterHdr `
  -NativeLibrary ./outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll `
  -RuntimeDirectory ./work/dlss-sdk/lib/Windows_x86_64/rel -TimeoutSeconds 300
```

Each directory must be new. These paths refer to locally locked dependencies;
this is not evidence of independent-checkout release reproducibility.

## Remaining investigation

The static fixture does not reproduce the user's failure. It does not cover
walking, swing/equip transitions, server resource packs, actual server scene,
post-effect targets, or all user pack options. The old forced-jitter flag alone
is not established as its cause. Keep the bug open and add moving/transition
captures with per-frame world/hand depth evidence if it recurs. Preserve the
working renderer while returning visual development to forest/river lighting,
foliage and water; further DLSS features and optimization remain deferred.
