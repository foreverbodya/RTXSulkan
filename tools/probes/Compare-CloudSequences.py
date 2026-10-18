"""Compare two actual adjacent-frame studies; report unmatched jitter/geometry explicitly."""
import argparse
import json
from pathlib import Path
import numpy as np


def aggregate(data):
    groups = {}
    for group in ["opaqueCloud", "partialCloud", "thresholdBand", "clearSky"]:
        groups[group] = {}
        for channel in ["input", "reconstructed"]:
            items = [pair["groups"][group][channel] for pair in data["pairs"]
                     if pair["historyValid"] and pair["groups"][group][channel]["samples"]]
            count = sum(item["samples"] for item in items)
            groups[group][channel] = {"samples": count,
                "weightedMeanRelativeLuminanceDelta": sum(item["samples"] * item["meanRelativeLuminanceDelta"] for item in items) / count if count else None,
                "maxPairP95": max((item["p95RelativeLuminanceDelta"] for item in items), default=None)}
    return {"all23PairsHistoryValid": data["allPairsHistoryValid"],
            "opacityThresholdCrossings": sum(pair.get("opacityThresholdCrossings", 0) for pair in data["pairs"]), "groups": groups}


def compare(capture_a, capture_b, analysis_a, analysis_b):
    report = {"finite": aggregate(json.loads((analysis_a / "analysis.json").read_text())),
              "sky": aggregate(json.loads((analysis_b / "analysis.json").read_text()))}
    a = json.loads((capture_a / "cloud-frame-sequence.json").read_text())["frames"]
    b = json.loads((capture_b / "cloud-frame-sequence.json").read_text())["frames"]
    if len(a) != 24 or len(b) != 24:
        raise ValueError("Expected two complete 24-frame sequences")
    pose = view = projection = raw_offset = 0.0
    jitter_differences = 0
    for i, (left, right) in enumerate(zip(a, b)):
        if left["pathSeconds"] != right["pathSeconds"] or left["sequenceIndex"] != right["sequenceIndex"] or left["sequenceIndex"] != i:
            raise ValueError(f"Commanded sequence path differs at {i}")
        pose = max(pose, float(np.max(np.abs(np.array(left["eye"]) - np.array(right["eye"])))))
        fa = np.fromfile(capture_a / f"cloud-sequence-{i:03d}-frame.bin", dtype="<f4")
        fb = np.fromfile(capture_b / f"cloud-sequence-{i:03d}-frame.bin", dtype="<f4")
        view = max(view, float(np.max(np.abs(fa[16:32] - fb[16:32]))))
        jitter_differences += not np.array_equal(fa[268:270], fb[268:270])
        def unjitter(f):
            p = f[:16].astype(float).reshape(4, 4).T.copy()
            p[0] -= (2 * f[268] / f[104]) * p[3]
            p[1] -= (2 * f[269] / f[105]) * p[3]
            return p
        projection = max(projection, float(np.max(np.abs(unjitter(fa) - unjitter(fb)))))
        oa, ob = dict(left["options"]), dict(right["options"])
        if oa.pop("CLOUD_RECONSTRUCTION_GUIDES") != 1 or ob.pop("CLOUD_RECONSTRUCTION_GUIDES") != 0 or oa != ob:
            raise ValueError("Comparison options differ beyond finite/sky guide choice")
        # The recorded raw cloud offset advances before tick freeze. The shader
        # returns effective phase 0 under this option, so test that contract.
        if oa.get("CLOUD_PREVIEW_STILL") != 1:
            raise ValueError("This comparison requires effective static cloud phase")
        raw_offset = max(raw_offset, float(abs(fa[140] - fb[140])))
        if not np.array_equal(fa[141:144], fb[141:144]) or not np.array_equal(fa[108:120], fb[108:120]) or not np.array_equal(fa[136:140], fb[136:140]):
            raise ValueError("Cloud bottom/blend or sky/solar uniforms differ")
    if pose > 1e-6 or view > 1e-6 or projection > 2e-6:
        raise ValueError(f"Actual camera mismatch: eye {pose}, view {view}, projection {projection}")
    source_a = json.loads((capture_a / "cloud-field-day-inputs.json").read_text())
    source_b = json.loads((capture_b / "cloud-field-day-inputs.json").read_text())
    for key in ["sourcePngSha256", "noiseSourcePngSha256", "densityModel", "phase", "bottom", "thickness", "extinction"]:
        if source_a[key] != source_b[key]:
            raise ValueError(f"Cloud source differs: {key}")
    report["pathAndCloudInputsMatch"] = {"frames": 24, "maxEyeDifferenceMeters": pose,
        "maxViewMatrixDifference": view, "maxUnjitteredProjectionDifference": projection,
        "framesWithDifferentJitter": jitter_differences, "unusedRawCloudOffsetDifference": raw_offset,
        "effectiveCloudPhase": 0, "sourcePngHashesAndOtherPackOptionsEqual": True,
        "sourceGeometryEquality": "not established; accepted meshes can stream; metrics select clear base depth in both neighboring frames"}
    report["scope"] = "Two real runs, same commanded/actual poses and cloud inputs except guide mode. Jitter phases and terrain streaming are not matched; masks/warp correspondences differ; no high-sample reference or ghosting oracle. Lower residual can mean smoothing/lag, not necessarily greater fidelity. Default stays off."
    report["cinematicAcceptance"] = False
    report["temporalVisualAcceptance"] = False
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for arg in ["capture_a", "capture_b", "analysis_a", "analysis_b", "output"]:
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError("Comparison report must be new")
    try:
        result = compare(args.capture_a, args.capture_b, args.analysis_a, args.analysis_b)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
    except Exception as error:
        args.output.write_text(json.dumps({"comparisonCompleted": False, "failure": repr(error)}, indent=2) + "\n")
        raise
