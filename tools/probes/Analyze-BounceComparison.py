"""Summarize a completed low/high/low same-scene run without visual acceptance."""
import json
import sys
from pathlib import Path

root, output = map(Path, sys.argv[1:])
terminal = json.loads((root / "result.json").read_text(encoding="utf-8-sig"))
atmosphere = terminal.get("atmosphereComparison", False)
sampling = terminal.get("samplingComparison", False)
if not terminal["passed"] or not terminal["runtimeModsUnchanged"] or not (terminal["bounceComparison"] or atmosphere or sampling):
    raise ValueError("A successful terminal comparison with unchanged mods is required")
evidence = root / "evidence"
comparison = json.loads((evidence / ("sampling-comparison.json" if sampling else "atmosphere-comparison.json" if atmosphere else "bounce-comparison.json")).read_text(encoding="utf-8-sig"))
if len(comparison["trials"]) != 6:
    raise ValueError("Six captures are required")
report = {"artifactSha256": terminal["artifactSha256"], "phases": {}}
option = "INDIRECT_SAMPLES" if sampling else "ATM_MULTIPLE_SCATTERING" if atmosphere else "INDIRECT_BOUNCES"
low, high = (1, 16) if sampling else (0, 1) if atmosphere else (terminal.get("comparisonLowBounces", 1), terminal.get("comparisonHighBounces", 2))
if not (sampling or atmosphere or 1 <= low < high <= 8):
    raise ValueError("Invalid comparison depths")
sequence = (low, high, low)
report["comparisonSequence"] = sequence
if not (atmosphere or sampling):
    report["bounceSequence"] = sequence
report["comparisonOption"] = option
for phase in ("day", "sunset"):
    trials = [t for t in comparison["trials"] if t["phase"] == phase]
    if [t.get("comparisonValue", t["diffuseBounces"]) for t in trials] != list(sequence) or [t["trial"] for t in trials] != [1, 2, 3]:
        raise ValueError("Wrong trial sequence")
    if len({t["geometrySha256"] for t in trials}) != 1:
        raise ValueError("Source geometry digests differ")
    other_options = [{k: v for k, v in t["activeOptions"].items() if k != option} for t in trials]
    if any(o != other_options[0] for o in other_options[1:]):
        raise ValueError("Other renderer options changed")
    ray_counts = []
    for t in trials:
        if not t["steadyGeometry"] or not t["unthrottled"] or not t["serverFrozen"]:
            raise ValueError("Comparison was not steady, unthrottled and frozen")
        if t["activeOptions"][option] != t.get("comparisonValue", t["diffuseBounces"]):
            raise ValueError("Renderer option differs from trial label")
        if not t["poseCheckedEveryFrame"] or t["measuredSeconds"] < 20 or t["frames"] < 100:
            raise ValueError("Camera validation or timing coverage is insufficient")
        reconstruction = t["reconstruction"]
        if tuple(reconstruction[k] for k in ("inputWidth", "inputHeight", "outputWidth", "outputHeight")) != (1707, 960, 2560, 1440):
            raise ValueError("Expected 1440p DLSS Quality extents")
        for endpoint in ("raySceneStart", "raySceneEnd"):
            ray = t[endpoint]
            if ray["pendingSections"]:
                raise ValueError("RT scene had pending geometry")
            ray_counts.append(tuple(ray[k] for k in ("sections", "meshes", "attributeBytes")))
    if len(set(ray_counts)) != 1:
        raise ValueError("Logical RT scene counts differ between trials")
    a, b, c = trials
    baseline = (a["meanFrameMs"] + c["meanFrameMs"]) / 2
    stats = []
    exposure = []
    for i, count in enumerate(sequence, start=1):
        name = f"{phase}-{'samples' if sampling else 'multiple' if atmosphere else 'bounces'}-{count}-trial-{i}"
        stats.append(json.loads((evidence / f"{name}-hdr-statistics.json").read_text(encoding="utf-8-sig")))
        exposure_path = evidence / f"{name}-exposure.json"
        exposure.append(json.loads(exposure_path.read_text(encoding="utf-8-sig")) if exposure_path.exists() else None)
    if len({(s["width"], s["height"]) for s in stats}) != 1:
        raise ValueError("HDR extents differ")
    regions = {}
    for region in ("whole", "centralShadowRoi"):
        values = [s[region]["meanLuminance"] for s in stats]
        reference = (values[0] + values[2]) / 2
        regions[region] = {"meanLuminanceSequence": values,
                           ("higherSampleRatioVsBracketMean" if sampling else "multipleScatteringRatioVsBracketMean" if atmosphere else "higherBounceRatioVsBracketMean"): values[1] / reference if reference else None,
                           ("baselineFrameDifferenceFraction" if atmosphere or sampling else "lowerBounceFrameDifferenceFraction"): (values[2] - values[0]) / reference if reference else None}
    drift = (c["meanFrameMs"] - a["meanFrameMs"]) / baseline
    report["phases"][phase] = {"geometrySha256": a["geometrySha256"],
        "meanFrameMsSequence": [t["meanFrameMs"] for t in trials],
        "meanFpsSequence": [t["meanFPS"] for t in trials],
        "p95FrameMsSequence": [t["p95FrameMs"] for t in trials],
        "p99FrameMsSequence": [t["p99FrameMs"] for t in trials],
        "baselineBracketMeanMs": baseline, ("higherSampleDeltaVsBracketMeanMs" if sampling else "multipleScatteringDeltaVsBracketMeanMs" if atmosphere else "higherBounceDeltaVsBracketMeanMs"): b["meanFrameMs"] - baseline,
        ("baselineTimingDriftFraction" if atmosphere or sampling else "lowerBounceTimingDriftFraction"): drift, "timingDriftExceedsFivePercent": abs(drift) > .05,
        "sourceHdr": regions, "exposure": exposure}
report["scope"] = ("Same frozen loaded world, equal captured source geometry digests and steady unthrottled intervals. "
                   "Bracketed wall cadence, not GPU pass timings or a statistical significance test. HDR statistics are single stochastic frames "
                   "before exposure/SR, not converged illumination; displayed frames retain adaptive exposure. Not visual or gameplay acceptance.")
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
