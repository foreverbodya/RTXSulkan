"""Summarize actual QA profiles, preserving scopes and unmodified per-pass measurements."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import statistics
from pathlib import Path


def cpu_stage_delta(before, after):
    if not before or not after:return None
    rows={}
    for name,last in after.get("stages",{}).items():
        first=before["stages"][name]
        rows[name]={"calls":last["calls"]-first["calls"],
                    "requestedWorkUnits":last["requestedWorkUnits"]-first["requestedWorkUnits"],
                    "workUnit":last["workUnit"],"measurement":last["measurement"]}
        for key in ("wallNanoseconds","activeThreadCpuNanoseconds","javaAllocatedBytes"):
            rows[name][key]=last[key]-first[key] if key in last and key in first else None
    return {"stages":rows,"scope":after["scope"],
            "activeThreadCpuAvailable":after["activeThreadCpuAvailable"],
            "javaAllocatedBytesAvailable":after["javaAllocatedBytesAvailable"]}


def load_run(root):
    root = Path(root)
    evidence = root / "evidence"
    artifact = json.loads((root / "result.json").read_text(encoding="utf-8"))
    assessment_path = root / "performance-assessment.json"
    assessment = json.loads(assessment_path.read_text(encoding="utf-8")) if assessment_path.exists() else None
    invalid_reasons = assessment.get("invalidReasons", []) if assessment else []
    if not isinstance(invalid_reasons, list) or any(not isinstance(reason, str) or not reason.strip() for reason in invalid_reasons):
        raise ValueError(f"{assessment_path}: invalidReasons must be a list of nonempty strings")
    if artifact.get("rayQueryCounts"):
        raise ValueError(f"{root}: instrumented ray-count run has no performance baseline; use ray-query-counts-*.json")
    if artifact.get("temporalProfile"):
        raise ValueError(f"{root}: independent image sequence has no performance baseline; use Analyze-TemporalProfile.py")
    completed = (evidence / "performance-profile.json").exists()
    combined = json.loads((evidence / "performance-profile.json").read_text(encoding="utf-8")) if completed else {
        "reports": sorted((json.loads(f.read_text(encoding="utf-8")) for f in evidence.glob("profile-*.json") if f.name!="profile-plan.json"),key=lambda s:s["startEpochMs"])}
    # Individual reports are authoritative; the first harness version shared list references
    # in its combined report. Scalar means are unaffected, but do not reuse those lists.
    reports = []
    for entry in combined["reports"]:
        name = entry["phase"]
        s = json.loads((evidence / f"profile-{name}.json").read_text(encoding="utf-8"))
        g = json.loads((evidence / f"gpu-pass-timings-profile-{name}.json").read_text(encoding="utf-8"))
        row = {
            "phase": name,
            "wallMs": s["wallMs"]["mean"],
            "fps": 1000 / s["wallMs"]["mean"],
            "renderThreadActiveCpuMs": s["renderThreadActiveCpuMs"]["mean"],
            "gpuWorldMs": g["passes"]["world.total"]["mean"],
            "blasBuilds": s["rayEnd"]["blasBuilds"] - s["rayStart"]["blasBuilds"],
            "tlasBuilds": s["rayEnd"]["tlasBuilds"] - s["rayStart"]["tlasBuilds"],
            "geometryStable": s["geometryStartSha256"] == s["geometryEndSha256"],
            "geometryStart": s["geometryStartSha256"],
            "geometryEnd": s["geometryEndSha256"],
            "nativeLiveMiBEnd": s["nativeMemoryEnd"]["liveAllocationBytes"] / 2**20,
            "nativeLifetimePeakMiB": s["nativeMemoryEnd"]["peakAllocationBytes"] / 2**20,
            "allocationFailures": s["nativeMemoryEnd"]["allocationFailures"],
            "allocations": s["nativeMemoryEnd"]["allocations"] - s["nativeMemoryStart"]["allocations"],
            "uploadMiB": (s["nativeMemoryEnd"]["uploadBytes"] - s["nativeMemoryStart"]["uploadBytes"]) / 2**20,
            "readbackMiB": (s["nativeMemoryEnd"]["readbackBytes"] - s["nativeMemoryStart"]["readbackBytes"]) / 2**20,
            "frames": s["frames"],
            "gpuTimingPrefixFrames": s.get("gpuTimingPrefixFrames", s["frames"]),
            "stressOnly": s.get("stressOnly", False),
            "wallP95Ms": s["wallMs"]["p95"],
            # Older reports label only QA alternate-pipeline selection. Normal
            # 3D packs select the candidate by their binding at compile time.
            "candidateSelected": s.get("candidateSelected", False) if artifact.get("workgroupExperiment") or artifact.get("canonicalPackComparison") else artifact.get("computeExperiment", "").startswith("cloud-3d"),
            "reportedAlternatePipelineSelection": s.get("candidateSelected",False),
            "sourceRevisionUnchanged": s.get("sourceRevisionUnchanged"),
            "syncCpuMsPerFrame": (s["rayEnd"]["syncCpuNanoseconds"] - s["rayStart"]["syncCpuNanoseconds"]) / 1e6 / s["frames"],
            "rayEnd": s["rayEnd"],
            "workCountersDelta": {k: v-s["workCountersStart"][k] for k,v in s["workCountersEnd"].items()},
            "configuration": {k:s.get(k) for k in ("seed","generator","camera","renderDistance", "worldTime", "options", "vsync", "poseCheckedEveryFrame")},
            "posePathSha256":hashlib.sha256(json.dumps([x["pose"] for x in s["perFrame"]],separators=(",",":")).encode()).hexdigest(),
            "passes": g["passes"],
        }
        row["configuration"]["reconstruction"]={k:v for k,v in s["reconstruction"].items() if k!="evaluations"}
        row["geometryCpuDelta"]=cpu_stage_delta(s.get("geometryCpuStart"),s.get("geometryCpuEnd"))
        row["geometryPreparationCpuDelta"]=cpu_stage_delta(s.get("geometryPreparationCpuStart"),s.get("geometryPreparationCpuEnd"))
        row["geometryPreparationWallMs"]=s.get("geometryPreparationWallMs")
        row["qaTelemetryActiveCpuMs"]=s.get("qaTelemetryActiveCpuMs")
        row["qaTelemetryWallMs"]=s.get("qaTelemetryWallMs")
        memory = [x["vulkanMemory"] for x in s["perFrame"] if x.get("vulkanMemory", {}).get("available")]
        if memory:
            row["processHeapEstimatedPeakMiB"] = max(x["deviceLocalEstimatedUsage"] for x in memory) / 2**20
            row["processHeapEstimatedEndMiB"] = memory[-1]["deviceLocalEstimatedUsage"] / 2**20
            row["processHeapMinimumBudgetMiB"] = min(x["deviceLocalBudget"] for x in memory) / 2**20
        row["nativeTimedPeakMiB"] = max(x["nativeAllocationBytes"] for x in s["perFrame"]) / 2**20
        telemetry = next(root.glob("*-gpu.csv"), None)
        if telemetry and "startEpochMs" in s:
            samples = []
            discarded = 0
            for r in csv.reader(telemetry.open()):
                # Stopping the owned sampler can leave a truncated final line. Preserve the
                # source and report discarded rows; do not synthesize missing telemetry.
                try:
                    if len(r)!=8:
                        raise ValueError("Incomplete telemetry row")
                    when = dt.datetime.strptime(r[0].strip(), "%Y/%m/%d %H:%M:%S.%f").timestamp() * 1000
                    float(r[1]); float(r[3])
                except (ValueError,IndexError):
                    discarded += 1
                    continue
                if s["startEpochMs"] <= when <= s["endEpochMs"]:
                    samples.append(r)
            row["telemetryDiscardedRowsInRun"] = discarded
            if samples:
                row["deviceVramSamplePeakMiB"] = max(float(r[3]) for r in samples)
                row["deviceGpuUtilMeanPercent"] = sum(float(r[1]) for r in samples) / len(samples)
                row["deviceGpuUtilSamples"] = len(samples)
        reports.append(row)
        row["measurementInvalidReasons"] = invalid_reasons
    return {"root": str(root.resolve()), "reports": reports, "allPhasesCompleted":completed,
            "performanceAssessment": assessment,
            "artifact": artifact,
            "scope": "GPU world excludes UI/swapchain. CPU is render-thread active CPU, not all process CPU. Native allocation bytes exclude engine/Sodium/NGX. Device VRAM/usage are WDDM device-wide 500ms samples, not isolated process residency or guaranteed instantaneous peaks."}


def comparisons(run):
    """Bracket a candidate with same-scene reference phases; never pair streaming endpoints."""
    phases = {r["phase"]:r for r in run["reports"]}
    output = []
    for phase in ("static", "motion"):
        keys = (phase, "narrow-"+phase, phase+"-repeat")
        if not all(k in phases for k in keys):
            continue
        a,b,c = [phases[k] for k in keys]
        geometry = {r[k] for r in (a,b,c) for k in ("geometryStart", "geometryEnd")}
        invalid=list(dict.fromkeys(reason for r in (a,b,c) for reason in r.get("measurementInvalidReasons", [])))
        if [r["candidateSelected"] for r in (a,b,c)]!=[False,True,False]:invalid.append("selection is not reference/candidate/reference")
        if len(geometry)!=1:invalid.append("geometry differs")
        if not (a["configuration"]==b["configuration"]==c["configuration"]):invalid.append("configuration differs")
        if len({r["posePathSha256"] for r in (a,b,c)})!=1:invalid.append("camera path differs")
        if len({(r["frames"],r["gpuTimingPrefixFrames"]) for r in (a,b,c)})!=1:invalid.append("sample window differs")
        if any(not r["sourceRevisionUnchanged"] for r in (a,b,c)):invalid.append("source revision changed within a window")
        mean = lambda field: statistics.mean((a[field], c[field]))
        before = mean("wallMs")
        passes = []
        for name in sorted(a["passes"].keys() | b["passes"].keys() | c["passes"].keys()):
            # Upload/AS rows contain events, not one sample per frame. Amortize their totals.
            def milliseconds(r):
                if name not in r["passes"]:return 0.0 # graph does not schedule this pass
                p = r["passes"][name]
                if name in ("geometry.upload", "blas.build", "tlas.build"):
                    return sum(p["milliseconds"]) / r["gpuTimingPrefixFrames"]
                return p["mean"]
            baseline = statistics.mean((milliseconds(a), milliseconds(c)))
            after = milliseconds(b)
            passes.append({"pass":name,"beforeMs":baseline,"afterMs":after,"savedMs":baseline-after,
                           "scope":"amortized per frame" if name in ("geometry.upload","blas.build","tlas.build") else "interval mean"})
        output.append({"phase":phase,"valid":not invalid,"invalidReasons":invalid,"geometryMatched":len(geometry)==1,
                       "referenceWallMs":before,"candidateWallMs":b["wallMs"],
                       "referenceDriftPercent":100*(c["wallMs"] / a["wallMs"]-1),
                       "fpsGainPercent":100*(before/b["wallMs"]-1) if not invalid else None,
                       "referenceFps":1000/before,"candidateFps":b["fps"],
                       "referenceGpuMs":mean("gpuWorldMs"),"candidateGpuMs":b["gpuWorldMs"],
                       "referenceActiveCpuMs":mean("renderThreadActiveCpuMs"),"candidateActiveCpuMs":b["renderThreadActiveCpuMs"],
                       "candidate":b,"passes":passes})
    return output


def quality_summary(root):
    root=Path(root)
    all_pairs=json.loads((root/"evidence/native-frame-comparison.json").read_text(encoding="utf-8"))
    a=[x for x in all_pairs if x.get("comparison","candidate")=="candidate"]
    self_pairs=[x for x in all_pairs if x.get("comparison")=="self"]
    h=json.loads((root/"evidence/workgroup-verification.json").read_text(encoding="utf-8"))
    colors={phase:json.loads((root/f"evidence/profile-{phase}.json").read_text(encoding="utf-8"))["reconstruction"]["colorResource"] for phase in {x["phase"] for x in h}}
    hdr=[x for x in h if x["resource"]==colors[x["phase"]]]
    return {"root":str(root.resolve()),"pairs":len(a),"changedPixels":sum(x["changedPixels"] for x in a),
            "maximumRGBCodeDelta":max(x["maximumRGBCodeDelta"] for x in a),
            "motionPairs":sum(x["phase"]=="motion" for x in a),
            "motionPairsWithNonzeroCameraDelta":sum(x["phase"]=="motion" and any(x["cameraDelta"]) for x in a),
            "hdrSelfComparisons":sum(x["pass"]=="frame.self" for x in hdr),
            "hdrSelfIdentical":all(x["byteIdentical"] for x in hdr if x["pass"]=="frame.self"),
            "hdrCandidateIdentical":all(x["byteIdentical"] for x in hdr if x["pass"]=="frame.candidate"),
            "allPhysicalInputSelfComparisons":sum(x["pass"]=="frame.self" for x in h),
            "allPhysicalInputsSelfIdentical":all(x["byteIdentical"] for x in h if x["pass"]=="frame.self"),
            "allPhysicalInputsCandidateIdentical":all(x["byteIdentical"] for x in h if x["pass"]=="frame.candidate"),
            "nativeSelfPairs":len(self_pairs),
            "nativeSelfChangedPixels":sum(x["changedPixels"] for x in self_pairs),
            "nativeSelfMaximumRGBCodeDelta":max((x["maximumRGBCodeDelta"] for x in self_pairs),default=None),
            "artifact":json.loads((root/"result.json").read_text(encoding="utf-8"))["artifactSha256"]}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("runs", nargs="+")
    p.add_argument("--output", required=True)
    p.add_argument("--quality-runs", nargs="*", default=[])
    p.add_argument("--bracket-runs", nargs=3, metavar=("REFERENCE","CANDIDATE","REFERENCE_REPEAT"))
    args = p.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    runs = [load_run(r) for r in args.runs]
    (output / "analysis.json").write_text(json.dumps(runs, indent=2))
    quality = [quality_summary(r) for r in args.quality_runs]
    (output / "quality.json").write_text(json.dumps(quality, indent=2))
    comparison = [{"root":r["root"],"comparisons":comparisons(r)} for r in runs]
    if args.bracket_runs:
        bracket=[load_run(p) for p in args.bracket_runs]
        reports=[]
        for i,r in enumerate(bracket):
            for s in r["reports"]:
                if s["phase"] in ("static","motion"):
                    reports.append(dict(s,phase=("narrow-" if i==1 else "")+s["phase"]+("-repeat" if i==2 else "")))
        comparison.append({"root":"canonical-three-process-bracket","runs":[r["root"] for r in bracket],"comparisons":comparisons({"reports":reports})})
    (output / "comparison.json").write_text(json.dumps(comparison, indent=2))
    with (output / "passes.csv").open("w", newline="") as f:
        writer=csv.writer(f)
        writer.writerow(("run","phase","pass","events","intervalMeanMs","intervalP95Ms","amortizedMsPerGpuWindowFrame","gpuWindowFrames","stressOnly"))
        for run in runs:
            for r in run["reports"]:
                for name,p in sorted(r["passes"].items()):
                    writer.writerow((Path(run["root"]).name,r["phase"],name,p["events"],p.get("mean"),p.get("p95"),sum(p["milliseconds"])/r["gpuTimingPrefixFrames"],r["gpuTimingPrefixFrames"],r["stressOnly"]))
    pass_lines=["Per-pass A/B/A means in ms. AS/upload rows are amortized event totals. World total is nested; do not sum rows.", ""]
    for run in comparison:
        for c in run["comparisons"]:
            if not c["valid"]:
                pass_lines += [f'## {Path(run["root"]).name} / {c["phase"]}',"","Invalid comparison: "+"; ".join(c["invalidReasons"])+". Raw phase measurements remain in passes.csv.",""]
                continue
            pass_lines += [f'## {Path(run["root"]).name} / {c["phase"]}',"", "| GPU interval | Before ms | After ms | Saved ms |", "| --- | ---: | ---: | ---: |"]
            pass_lines += [f'| {p["pass"]} | {p["beforeMs"]:.4f} | {p["afterMs"]:.4f} | {p["savedMs"]:.4f} |' for p in sorted(c["passes"],key=lambda p:-p["beforeMs"])]
            pass_lines.append("")
    (output / "passes-before-after.md").write_text("\n".join(pass_lines))
    lines = ["| Run / phase | FPS | CPU active ms | GPU world ms | BLAS / TLAS builds | Native live MiB | Stable geometry |",
             "| --- | ---: | ---: | ---: | ---: | ---: | --- |"]
    for run in runs:
        for r in run["reports"]:
            lines.append(f'| {Path(run["root"]).name} / {r["phase"]} | {r["fps"]:.2f} | {r["renderThreadActiveCpuMs"]:.2f} | {r["gpuWorldMs"]:.2f} | {r["blasBuilds"]} / {r["tlasBuilds"]} | {r["nativeLiveMiBEnd"]:.1f} | {r["geometryStable"]} |')
    (output / "summary.md").write_text("\n".join(lines) + "\n")
    paired=["| Run / path | Valid comparison | FPS before → after | CPU active ms before → after | GPU ms before → after | FPS gain | Reference drift |", "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for run in comparison:
        for c in run["comparisons"]:
            if not c["valid"]:
                paired.append(f'| {Path(run["root"]).name} / {c["phase"]} | No: {"; ".join(c["invalidReasons"])} | — | — | — | — | — |')
                continue
            paired.append(f'| {Path(run["root"]).name} / {c["phase"]} | Yes | {c["referenceFps"]:.2f} → {c["candidateFps"]:.2f} | {c["referenceActiveCpuMs"]:.2f} → {c["candidateActiveCpuMs"]:.2f} | {c["referenceGpuMs"]:.2f} → {c["candidateGpuMs"]:.2f} | {c["fpsGainPercent"]:+.2f}% | {c["referenceDriftPercent"]:+.2f}% |')
    (output / "before-after.md").write_text("\n".join(paired)+"\n", encoding="utf-8")
    hashes = {}
    for run in args.runs:
        root = Path(run)
        for f in sorted((root / "evidence").glob("*.json")):
            hashes[str(f.resolve())] = hashlib.sha256(f.read_bytes()).hexdigest()
        assessment_path = root / "performance-assessment.json"
        if assessment_path.exists():
            hashes[str(assessment_path.resolve())] = hashlib.sha256(assessment_path.read_bytes()).hexdigest()
    (output / "report-sha256.json").write_text(json.dumps(hashes, indent=2))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
