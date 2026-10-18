"""Inspect retained real temporal A/A/B/A evidence; never infer perceptual acceptance.

Usage: python Analyze-TemporalProfile.py RUN --output SUMMARY.json
Only reads source evidence. RGB8 pairs are streamed one frame at a time, with
one previous signed error image retained for pixel-space error-change metrics.
"""
import argparse
import gzip
import hashlib
import json
import math
import re
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image


PHASES = ("reference-1", "reference-2", "candidate", "reference-3")
ALIASES = dict(zip(PHASES, ("A1", "A2", "B", "A3")))
PAIRS = (("A1/A2", "reference-2"), ("A1/B", "candidate"), ("A1/A3", "reference-3"))
THRESHOLDS = (1, 2, 4, 8, 16, 32, 64)
SPARSE = (0, 63, 127)
FORMATS = {
    "R16_FLOAT": ("<f2", 1), "RG16_FLOAT": ("<f2", 2), "RGBA16_FLOAT": ("<f2", 4),
    "R32_FLOAT": ("<f4", 1), "RG32_FLOAT": ("<f4", 2), "RGBA32_FLOAT": ("<f4", 4),
}
UNIFORM_SIZE = 1152
EXCLUDED_AS_BYTES = (1120, 1132)
# Leave headroom below 1 GiB for Pillow decoding, numpy reductions and libraries.
# Actual 2560x1440 evidence is far below this conservative working-set guard.
WORKING_LIMIT = 900 * 1024 * 1024


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Analysis:
    def __init__(self, root):
        self.root = root.resolve()
        self.evidence = self.root / "evidence" if (self.root / "evidence").is_dir() else self.root
        self.failures = []
        self.file_hashes = {}

    def fail(self, scope, detail, **extra):
        self.failures.append({"scope": scope, "detail": detail, **extra})

    def require(self, condition, scope, detail, **extra):
        if not condition:
            self.fail(scope, detail, **extra)
        return bool(condition)

    def hash_file(self, path):
        relative = str(path.relative_to(self.root))
        if relative not in self.file_hashes:
            self.file_hashes[relative] = sha256_file(path)
        return self.file_hashes[relative]

    def read_json(self, path):
        try:
            with path.open(encoding="utf-8-sig") as stream:
                result = json.load(stream)
            self.hash_file(path)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except Exception as error:
            self.fail("metadata", str(error), file=str(path))
            return {}

    def raw_frame(self, path):
        try:
            data = path.read_bytes()
            if len(data) != UNIFORM_SIZE:
                raise ValueError(f"Expected {UNIFORM_SIZE} uniform bytes, found {len(data)}")
            self.hash_file(path)
            return data
        except Exception as error:
            self.fail("uniform", str(error), file=str(path))
            return None

    def validate(self):
        profile = self.read_json(self.evidence / "temporal-profile.json")
        plan = self.read_json(self.evidence / "temporal-plan.json")
        candidate = self.read_json(self.evidence / "temporal-manifest-candidate.json")
        reference = self.read_json(self.evidence / "temporal-manifest-reference.json")
        self.require(profile.get("complete") is True, "execution", "temporal-profile is not complete")
        sequence = profile.get("sequence")
        self.require(sequence in ("static", "motion"), "metadata", "Sequence must be static or motion")
        self.require(plan.get("sequence") == sequence, "metadata", "Plan and completed sequence differ")
        self.require(plan.get("phases") == list(PHASES), "metadata", "Expected A1/A2/B/A3 phase order")
        self.require(plan.get("warmupFrames") == 256 and plan.get("captureFrames") == 128,
                     "metadata", "Expected 256 warmup frames and 128 captures")
        self.require(plan.get("sparseInputCaptureIndices") == list(SPARSE), "metadata", "Unexpected sparse capture indices")
        formats = plan.get("inputFormats", {})
        self.require(isinstance(formats, dict) and len(formats) == 5, "metadata", "Expected exactly five actual input resource formats")
        if not isinstance(formats, dict):
            formats = {}
        safe_formats = {}
        for resource, fmt in formats.items():
            if not self.require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+", resource)), "metadata", "Unsafe input resource name", resource=resource):
                continue
            if not self.require(fmt in FORMATS, "metadata", "Unsupported actual input format; no fallback type is assumed", resource=resource, format=fmt):
                continue
            safe_formats[resource] = fmt
            for manifest_name, manifest in (("reference", reference), ("candidate", candidate)):
                self.require(manifest.get("resources", {}).get(resource, {}).get("format") == fmt,
                             "metadata", "Plan format differs from actual manifest", resource=resource, manifest=manifest_name)
        combined = profile.get("phases", [])
        self.require(isinstance(combined, list) and [p.get("phase") for p in combined if isinstance(p, dict)] == list(PHASES),
                     "metadata", "Completed report must contain all four phases in order")
        combined_map = {p.get("phase"): p for p in combined if isinstance(p, dict)} if isinstance(combined, list) else {}
        phase_docs, directories, uniforms = {}, {}, {}
        geometry = profile.get("sourceGeometrySha256")
        self.require(isinstance(geometry, str) and bool(re.fullmatch(r"[a-fA-F0-9]{64}", geometry)), "geometry", "Missing exact source geometry digest")
        if sequence not in ("static", "motion"):
            return {"profile": profile, "plan": plan, "candidate": candidate, "formats": safe_formats,
                    "phases": {}, "directories": {}, "uniforms": {}, "outputSize": None, "inputSize": None}
        dimensions = None
        for ordinal, phase in enumerate(PHASES, 1):
            directory = self.evidence / f"temporal-{sequence}-{phase}"
            directories[phase] = directory
            doc = self.read_json(directory / "sequence.json")
            phase_docs[phase] = doc
            self.require(doc == combined_map.get(phase), "metadata", "Individual and completed phase reports differ", phase=phase)
            self.require(doc.get("phase") == phase and doc.get("sequence") == sequence, "metadata", "Wrong phase identity", phase=phase)
            self.require(doc.get("graphReloadOrdinal") == ordinal, "history", "Missing independent graph reload ordinal", phase=phase)
            self.require(doc.get("warmupFrames") == 256 and doc.get("captureFrames") == 128,
                         "history", "Unexpected history warmup/capture length", phase=phase)
            self.require(doc.get("sourceGeometrySha256") == geometry, "geometry", "Phase geometry differs", phase=phase)
            source_doc = self.read_json(directory / "source-geometry.json")
            self.require(source_doc.get("sha256") == geometry, "geometry", "Retained source geometry witness differs", phase=phase)
            self.require(doc.get("inputFormats") == formats, "metadata", "Phase input formats differ", phase=phase)
            self.require(doc.get("uniformComparisonExcludedByteRange") == list(EXCLUDED_AS_BYTES),
                         "uniform", "Unexpected uniform exclusion range", phase=phase)
            reconstruction = doc.get("reconstruction", {})
            try:
                actual_dimensions = tuple(int(reconstruction[key]) for key in ("inputWidth", "inputHeight", "outputWidth", "outputHeight"))
                if min(actual_dimensions) < 1:
                    raise ValueError("Nonpositive extent")
                if dimensions is None:
                    dimensions = actual_dimensions
                self.require(actual_dimensions == dimensions, "metadata", "Input/output resolution differs between phases", phase=phase)
            except Exception as error:
                self.fail("metadata", "Missing actual reconstruction dimensions: " + str(error), phase=phase)
            rows = doc.get("frames", [])
            self.require(isinstance(rows, list) and len(rows) == 128, "adjacency", "Expected 128 recorded adjacent captures", phase=phase)
            row_map = {row.get("index"): row for row in rows if isinstance(row, dict)} if isinstance(rows, list) else {}
            self.require(list(row_map) == list(range(128)), "adjacency", "Capture indices are not exactly 0..127 in order", phase=phase)
            phase_uniforms = []
            evaluations = []
            for index in range(128):
                row = row_map.get(index, {})
                scope = {"phase": phase, "index": index}
                self.require(row.get("logicalFrame") == 256 + index, "adjacency", "Logical frames must be 256..383", **scope)
                self.require(row.get("historyValid") is True, "history", "Captured frame has no valid history", **scope)
                self.require(row.get("uniformsMatchReferenceOutsideAsIdentity") is True, "uniform", "Harness reported unmatched physical inputs", **scope)
                evaluations.append(row.get("nativeEvaluations"))
                raw = self.raw_frame(directory / f"{index:03d}-frame.bin")
                phase_uniforms.append(raw)
                if raw is None:
                    continue
                self.require(hashlib.sha256(raw).hexdigest() == row.get("uniformSha256"), "uniform", "Uniform file digest differs from capture metadata", **scope)
                f = lambda offset: struct.unpack_from("<f", raw, offset)[0]
                u = lambda offset: struct.unpack_from("<I", raw, offset)[0]
                self.require(u(1132) == 256 + index and f(408) == float(256 + index), "adjacency", "Actual shader logical frame differs", **scope)
                self.require(f(396) == 1.0, "history", "Actual captured uniform history flag is not true", **scope)
                self.require(u(1128) == 0, "geometry", "Actual AS has pending sections in excluded identity range", **scope)
                self.require(f(400) == float(np.float32((256 + index) / 60.0)) and f(404) == float(np.float32(1 / 60.0)),
                             "adjacency", "Actual logical clock/delta differ", **scope)
                if dimensions:
                    self.require((f(416), f(420)) == dimensions[:2], "uniform", "Actual uniform input size differs", **scope)
                if phase != PHASES[0] and index < len(uniforms.get(PHASES[0], [])):
                    baseline = uniforms[PHASES[0]][index]
                    if baseline is not None:
                        a, b = EXCLUDED_AS_BYTES
                        if baseline[:a] != raw[:a] or baseline[b:] != raw[b:]:
                            first = next(p for p, (x, y) in enumerate(zip(baseline, raw)) if (p < a or p >= b) and x != y)
                            self.fail("uniform", "Actual uniform bytes differ outside AS identity", firstMismatchByte=first, **scope)
                if phase != PHASES[0]:
                    baseline_rows = phase_docs.get(PHASES[0], {}).get("frames", [])
                    if index < len(baseline_rows):
                        self.require(row.get("pose") == baseline_rows[index].get("pose"), "camera", "Recorded camera trajectory differs", **scope)
            if all(isinstance(value, int) for value in evaluations):
                self.require(all(b == a + 1 for a, b in zip(evaluations, evaluations[1:])), "adjacency", "Native evaluations are not adjacent", phase=phase)
            else:
                self.fail("adjacency", "Missing native evaluation indices", phase=phase)
            uniforms[phase] = phase_uniforms
        return {"profile": profile, "plan": plan, "candidate": candidate, "formats": safe_formats,
                "phases": phase_docs, "directories": directories, "uniforms": uniforms,
                "inputSize": dimensions[:2] if dimensions else None, "outputSize": dimensions[2:] if dimensions else None}


def error_metrics(error, psnr=True):
    absolute = np.abs(error)
    pixel_max = absolute.max(axis=2)
    total_abs = int(absolute.sum(dtype=np.int64))
    total_squared = int(np.square(error, dtype=np.int32).sum(dtype=np.int64))
    channels, pixels = int(error.size), int(pixel_max.size)
    mse = total_squared / channels
    result = {
        "pixels": pixels, "rgbValues": channels, "sumAbsoluteCodeError": total_abs,
        "sumSquaredCodeError": total_squared, "maximumAbsoluteCodeError": int(pixel_max.max()),
        "meanAbsoluteCodeError": total_abs / channels, "rootMeanSquareCodeError": math.sqrt(mse),
        "meanPixelMaximumCodeError": float(pixel_max.mean(dtype=np.float64)),
        "pixelsAtOrAbove": {str(t): int(np.count_nonzero(pixel_max >= t)) for t in THRESHOLDS},
    }
    if psnr:
        result.update({"psnrDb": 10 * math.log10(255.0 * 255.0 / mse) if mse else None,
                       "perfectRGBMatch": total_squared == 0, "psnrInfinite": mse == 0})
    return result


def aggregate_errors(rows, psnr=True):
    if not rows:
        return {"samples": 0}
    channels = sum(r["rgbValues"] for r in rows)
    pixels = sum(r["pixels"] for r in rows)
    absolute = sum(r["sumAbsoluteCodeError"] for r in rows)
    squared = sum(r["sumSquaredCodeError"] for r in rows)
    mse = squared / channels
    counts = {str(t): sum(r["pixelsAtOrAbove"][str(t)] for r in rows) for t in THRESHOLDS}
    result = {"samples": len(rows), "pixelObservations": pixels, "rgbObservations": channels,
              "sumAbsoluteCodeError": absolute, "sumSquaredCodeError": squared,
              "maximumAbsoluteCodeError": max(r["maximumAbsoluteCodeError"] for r in rows),
              "meanAbsoluteCodeError": absolute / channels, "rootMeanSquareCodeError": math.sqrt(mse),
              "pixelsAtOrAbove": counts, "pixelFractionAtOrAbove": {k: v / pixels for k, v in counts.items()}}
    if psnr:
        result.update({"psnrDb": 10 * math.log10(255.0 * 255.0 / mse) if mse else None,
                       "perfectRGBMatch": squared == 0, "psnrInfinite": mse == 0})
    return result


def read_rgb(analysis, path, size):
    with Image.open(path) as source:
        if source.format != "PNG" or source.mode not in ("RGB", "RGBA") or source.size != tuple(size):
            raise ValueError(f"Unexpected PNG format/mode/extent: {source.format}/{source.mode}/{source.size}, expected {size}")
        with source.convert("RGB") as converted:
            pixels = np.array(converted, dtype=np.uint8, copy=True)
    analysis.hash_file(path)
    return pixels


def display_comparisons(analysis, context):
    output = []
    size = context["outputSize"]
    if size is None:
        analysis.fail("display", "No verified output resolution")
        return output
    estimated_peak = size[0] * size[1] * 80 + 64 * 1024 * 1024
    if estimated_peak > WORKING_LIMIT:
        analysis.fail("memory", "Resolution exceeds conservative sub-1-GiB analysis guard", estimatedPeakBytes=estimated_peak)
        return output
    for label, candidate in PAIRS:
        frames, transitions = [], []
        previous_error = None
        previous_index = None
        for index in range(128):
            try:
                reference = read_rgb(analysis, context["directories"][PHASES[0]] / f"{index:03d}-display.png", size)
                actual = read_rgb(analysis, context["directories"][candidate] / f"{index:03d}-display.png", size)
                error = actual.astype(np.int16)
                error -= reference
                del reference, actual
                row = error_metrics(error)
                row.update({"index": index, "logicalFrame": index + 256})
                frames.append(row)
                if previous_error is not None and previous_index == index - 1:
                    delta = error - previous_error
                    transition = error_metrics(delta, psnr=False)
                    transition.update({"fromIndex": index - 1, "toIndex": index,
                                       "fromLogicalFrame": index + 255, "toLogicalFrame": index + 256})
                    transitions.append(transition)
                    del delta
                previous_error, previous_index = error, index
            except Exception as error:
                analysis.fail("display", str(error), comparison=label, index=index)
                previous_error = previous_index = None
        analysis.require(len(frames) == 128 and len(transitions) == 127, "display", "Not all adjacent pairs were analyzed", comparison=label)
        output.append({"comparison": label, "referencePhase": PHASES[0], "actualPhase": candidate,
                       "role": "same-implementation control" if candidate != "candidate" else "candidate",
                       "signedErrorDefinition": "E_t = actualRGB8_t - A1RGB8_t in encoded PNG RGB code values",
                       "aggregate": aggregate_errors(frames), "frames": frames,
                       "pixelSpaceErrorChange": {
                           "definition": "Delta E_t = E_t - E_(t-1) at the same pixel coordinates",
                           "scope": "Pixel-space change in paired error; not a motion-compensated flicker, temporal stability or perceptual metric. No optical flow or reprojection is applied.",
                           "aggregate": aggregate_errors(transitions, psnr=False), "transitions": transitions}})
    return output


def resource_extent(context, resource):
    definition = context["candidate"]["resources"][resource]
    if "size" in definition:
        extent = tuple(int(n) for n in definition["size"])
        if len(extent) != 3 or min(extent) < 1:
            raise ValueError("Invalid fixed resource extent")
        return extent
    scale = float(definition.get("scale", 1.0))
    if not math.isfinite(scale) or scale <= 0:
        raise ValueError("Invalid actual resource scale")
    width, height = context["inputSize"]
    return max(1, math.ceil(width * scale)), max(1, math.ceil(height * scale)), 1


def read_sparse(analysis, path, expected):
    if expected > WORKING_LIMIT // 20:
        raise ValueError("Sparse input exceeds conservative sub-1-GiB analysis guard")
    with gzip.open(path, "rb") as stream:
        data = stream.read(expected + 1)
    if len(data) != expected:
        raise ValueError(f"Decompressed extent differs: {len(data)} bytes, expected {expected}")
    analysis.hash_file(path)
    return data


def float_summary(values):
    return {"values": int(values.size), "finiteValues": int(np.count_nonzero(np.isfinite(values))),
            "nanValues": int(np.count_nonzero(np.isnan(values))),
            "positiveInfinityValues": int(np.count_nonzero(np.isposinf(values))),
            "negativeInfinityValues": int(np.count_nonzero(np.isneginf(values)))}


def sparse_comparisons(analysis, context):
    rows = []
    if context["inputSize"] is None:
        analysis.fail("sparseInput", "No verified input resolution")
        return rows
    for label, phase in PAIRS:
        for index in SPARSE:
            for resource, fmt in context["formats"].items():
                try:
                    dtype, components = FORMATS[fmt]
                    extent = resource_extent(context, resource)
                    count = math.prod(extent) * components
                    expected = count * np.dtype(dtype).itemsize
                    filename = f"{index:03d}-{resource}.bin.gz"
                    before = read_sparse(analysis, context["directories"][PHASES[0]] / filename, expected)
                    after = read_sparse(analysis, context["directories"][phase] / filename, expected)
                    a, b = np.frombuffer(before, dtype=dtype), np.frombuffer(after, dtype=dtype)
                    stats_a, stats_b = float_summary(a), float_summary(b)
                    valid = np.isfinite(a) & np.isfinite(b)
                    all_finite = bool(valid.all())
                    analysis.require(all_finite, "sparseInput", "Non-finite actual source values retained", comparison=label, index=index, resource=resource)
                    # Float64 subtraction/squares avoid float32 overflow, and actual
                    # float16/float32 decoding is selected from the captured format.
                    delta = b.astype(np.float64) - a
                    if not all_finite:
                        delta = delta[valid]
                    absolute = np.abs(delta)
                    numeric = {"mutuallyFiniteValues": int(delta.size), "allValuesFinite": all_finite,
                               "changedFiniteValues": int(np.count_nonzero(delta)),
                               "maximumAbsoluteError": float(absolute.max()) if delta.size else None,
                               "meanAbsoluteError": float(absolute.mean()) if delta.size else None,
                               "rootMeanSquareError": float(np.sqrt(np.mean(np.square(delta)))) if delta.size else None}
                    rows.append({"comparison": label, "index": index, "logicalFrame": index + 256,
                                 "resource": resource, "format": fmt, "extent": list(extent), "components": components,
                                 "bytes": expected, "byteIdentical": before == after,
                                 "changedBytes": int(np.count_nonzero(np.frombuffer(before, dtype=np.uint8) != np.frombuffer(after, dtype=np.uint8))),
                                 "referenceUncompressedSha256": hashlib.sha256(before).hexdigest(),
                                 "actualUncompressedSha256": hashlib.sha256(after).hexdigest(),
                                 "referenceValues": stats_a, "actualValues": stats_b, "numeric": numeric})
                except Exception as error:
                    analysis.fail("sparseInput", str(error), comparison=label, index=index, resource=resource)
    analysis.require(len(rows) == 45, "sparseInput", "Expected 3 comparisons x 3 sparse frames x 5 actual resources", analyzedPairs=len(rows))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    analysis = Analysis(args.run)
    summary = {"run": str(analysis.root), "analysisComplete": False, "evidenceValid": False,
               "perceptualAcceptance": None,
               "rgbMetricScope": "Encoded PNG RGB8 values, peak 255. PSNR is null with psnrInfinite=true for a perfect match. Pixel thresholds use the largest absolute channel error, not RGB sum. No gamma linearization or image modification.",
               "scope": "Actual independent sequential native display comparison. Numerical differences and A/A controls are reported without a perceptual threshold or visual-acceptance claim. Logical QA time, readback and PNG/GZIP I/O exclude performance/FPS claims. Original PNGs and raw source buffers are only read."}
    try:
        context = analysis.validate()
        summary.update({"sequence": context["profile"].get("sequence"), "sourceGeometrySha256": context["profile"].get("sourceGeometrySha256"),
                        "options": context["profile"].get("options"), "inputSize": context["inputSize"], "outputSize": context["outputSize"],
                        "phaseAliases": ALIASES, "uniformExcludedByteRange": list(EXCLUDED_AS_BYTES), "inputFormats": context["formats"]})
        if context["directories"]:
            summary["displayComparisons"] = display_comparisons(analysis, context)
            summary["sparseInputComparisons"] = sparse_comparisons(analysis, context)
            summary["analysisComplete"] = (len(summary["displayComparisons"]) == 3
                and all(len(c["frames"]) == 128 and len(c["pixelSpaceErrorChange"]["transitions"]) == 127 for c in summary["displayComparisons"])
                and len(summary["sparseInputComparisons"]) == 45)
        result_file = analysis.root / "result.json"
        if result_file.is_file():
            terminal = analysis.read_json(result_file)
            summary["terminalResult"] = {key: terminal.get(key) for key in ("passed", "exitCode", "timedOut", "activationFailure", "artifactSha256", "runtimeModHashes", "runtimeModsUnchanged", "temporalProfile")}
            analysis.require(terminal.get("passed") is True, "execution", "Runner terminal result is not successful")
    except Exception as error:
        analysis.fail("analysis", f"{type(error).__name__}: {error}")
    summary["failures"] = analysis.failures
    summary["evidenceValid"] = bool(summary["analysisComplete"] and not analysis.failures)
    summary["sourceFilesSha256"] = dict(sorted(analysis.file_hashes.items()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output.resolve()), "analysisComplete": summary["analysisComplete"],
                      "evidenceValid": summary["evidenceValid"], "failures": len(analysis.failures), "perceptualAcceptance": None}))
    return 0 if summary["evidenceValid"] else 1


if __name__ == "__main__":
    sys.exit(main())
