"""Summarize actual QA profiles, preserving scopes and unmodified per-pass measurements."""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path


def load_run(root):
    root = Path(root)
    evidence = root / "evidence"
    combined = json.loads((evidence / "performance-profile.json").read_text())
    # Individual reports are authoritative; the first harness version shared list references
    # in its combined report. Scalar means are unaffected, but do not reuse those lists.
    reports = []
    for entry in combined["reports"]:
        name = entry["phase"]
        s = json.loads((evidence / f"profile-{name}.json").read_text())
        g = json.loads((evidence / f"gpu-pass-timings-profile-{name}.json").read_text())
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
            "passes": g["passes"],
        }
        telemetry = next(root.glob("*-gpu.csv"), None)
        if telemetry and "startEpochMs" in s:
            samples = []
            for r in csv.reader(telemetry.open()):
                when = dt.datetime.strptime(r[0].strip(), "%Y/%m/%d %H:%M:%S.%f").timestamp() * 1000
                if s["startEpochMs"] <= when <= s["endEpochMs"]:
                    samples.append(r)
            if samples:
                row["deviceVramSamplePeakMiB"] = max(float(r[3]) for r in samples)
                row["deviceGpuUtilMeanPercent"] = sum(float(r[1]) for r in samples) / len(samples)
                row["deviceGpuUtilSamples"] = len(samples)
        reports.append(row)
    return {"root": str(root.resolve()), "reports": reports,
            "artifact": json.loads((root / "result.json").read_text()),
            "scope": "GPU world excludes UI/swapchain. CPU is render-thread active CPU, not all process CPU. Native allocation bytes exclude engine/Sodium/NGX. Device VRAM/usage are WDDM device-wide 500ms samples, not isolated process residency or guaranteed instantaneous peaks."}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("runs", nargs="+")
    p.add_argument("--output", required=True)
    args = p.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    runs = [load_run(r) for r in args.runs]
    (output / "analysis.json").write_text(json.dumps(runs, indent=2))
    lines = ["| Run / phase | FPS | CPU active ms | GPU world ms | BLAS / TLAS builds | Native live MiB | Stable geometry |",
             "| --- | ---: | ---: | ---: | ---: | ---: | --- |"]
    for run in runs:
        for r in run["reports"]:
            lines.append(f'| {Path(run["root"]).name} / {r["phase"]} | {r["fps"]:.2f} | {r["renderThreadActiveCpuMs"]:.2f} | {r["gpuWorldMs"]:.2f} | {r["blasBuilds"]} / {r["tlasBuilds"]} | {r["nativeLiveMiBEnd"]:.1f} | {r["geometryStable"]} |')
    (output / "summary.md").write_text("\n".join(lines) + "\n")
    hashes = {}
    for run in args.runs:
        root = Path(run)
        for f in sorted((root / "evidence").glob("*.json")):
            hashes[str(f.resolve())] = hashlib.sha256(f.read_bytes()).hexdigest()
    (output / "report-sha256.json").write_text(json.dumps(hashes, indent=2))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
