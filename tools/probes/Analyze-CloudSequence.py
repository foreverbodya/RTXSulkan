"""Analyze actual adjacent-frame captures, with no temporal-quality PASS threshold.

Warped deltas contain volume-proxy error, integration/sampling error and view
changes. They are diagnostics, not a ghosting oracle or high-sample reference.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def sample(a, x, y):
    h, w = a.shape[:2]
    x0, y0 = np.floor(x).astype(np.int32), np.floor(y).astype(np.int32)
    fx, fy = (x - x0).astype(np.float32)[..., None], (y - y0).astype(np.float32)[..., None]
    x1, y1 = np.clip(x0 + 1, 0, w - 1), np.clip(y0 + 1, 0, h - 1)
    x0, y0 = np.clip(x0, 0, w - 1), np.clip(y0, 0, h - 1)
    return ((a[y0, x0].astype(np.float32) * (1 - fx) + a[y0, x1].astype(np.float32) * fx) * (1 - fy)
            + (a[y1, x0].astype(np.float32) * (1 - fx) + a[y1, x1].astype(np.float32) * fx) * fy)


def depth_max(a, x, y):
    h, w = a.shape[:2]
    x0, y0 = np.floor(x).astype(np.int32), np.floor(y).astype(np.int32)
    x1, y1 = np.clip(x0 + 1, 0, w - 1), np.clip(y0 + 1, 0, h - 1)
    x0, y0 = np.clip(x0, 0, w - 1), np.clip(y0, 0, h - 1)
    return np.maximum.reduce([a[y0, x0, 0], a[y0, x1, 0], a[y1, x0, 0], a[y1, x1, 0]])


def stats(current, previous, mask):
    count = int(np.count_nonzero(mask))
    if not count:
        return {"samples": 0}
    weights = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    c, p = current[mask, :3], previous[mask, :3]
    lc, lp = c @ weights, p @ weights
    denominator = .01 + .5 * (np.abs(lc) + np.abs(lp))
    absolute = np.abs(lc - lp)
    relative = absolute / denominator
    return {"samples": count, "meanAbsoluteLuminanceDelta": float(absolute.mean()),
            "meanRelativeLuminanceDelta": float(relative.mean()),
            "p50RelativeLuminanceDelta": float(np.quantile(relative, .5)),
            "p95RelativeLuminanceDelta": float(np.quantile(relative, .95)),
            "p99RelativeLuminanceDelta": float(np.quantile(relative, .99)),
            "meanRelativeRgbDelta": float((np.mean(np.abs(c - p), axis=-1) / denominator).mean())}


def read_capture(root, index, metadata):
    stem = f"cloud-sequence-{index:03d}"
    actual = json.loads((root / f"{stem}.json").read_text())
    if actual != metadata:
        raise ValueError(f"Sequence metadata mismatch {index}")
    bindings = actual["bindings"]
    w, h = bindings["inputWidth"], bindings["inputHeight"]
    ow, oh = bindings["outputWidth"], bindings["outputHeight"]
    expected = [bindings["colorResource"], "$reconstructed", "$display", "cloud.reconstruction.guide",
                "reconstruction.depth", bindings["motionResource"], "display.exposure"]
    if actual["resources"] != expected:
        raise ValueError("Sequence resource order changed")
    specifications = [("<f2", (h, w, 4)), ("<f2", (oh, ow, 4)), ("u1", (oh, ow, 4)),
                      ("<f4", (h, w, 4)), ("<f4", (h, w, 1)), ("<f2", (h, w, 2)), ("<f4", (1, 1, 4))]
    arrays = []
    for resource, (dtype, shape) in enumerate(specifications):
        path = root / f"{stem}-buffer-{resource}.bin"
        if path.stat().st_size != np.prod(shape) * np.dtype(dtype).itemsize:
            raise ValueError(f"Bad buffer extent {path}")
        array = np.memmap(path, mode="r", dtype=dtype, shape=shape)
        if dtype != "u1" and not np.isfinite(array).all():
            raise ValueError(f"Nonfinite captured buffer {path}")
        arrays.append(array)
    f = np.fromfile(root / f"{stem}-frame.bin", dtype="<f4")
    if f.size != 288 or (int(f[104]), int(f[105])) != (w, h):
        raise ValueError(f"Frame dimensions/layout changed {index}")
    return {"metadata": actual, "bindings": bindings, "frame": f, "buffers": arrays}


def pair_metrics(previous, current):
    b, f = current["bindings"], current["frame"]
    pb, pf = previous["bindings"], previous["frame"]
    if (b["inputWidth"], b["inputHeight"], b["outputWidth"], b["outputHeight"]) != (pb["inputWidth"], pb["inputHeight"], pb["outputWidth"], pb["outputHeight"]):
        raise ValueError("Sequence dimensions changed")
    history = bool(f[99] >= .5)
    if history and (not np.array_equal(f[64:96].view("u4"), pf[:32].view("u4"))
                    or not np.array_equal(f[270:272].view("u4"), pf[268:270].view("u4"))):
        raise ValueError("Previous matrices/jitter do not belong to the preceding captured frame")
    record = {"previousIndex": previous["metadata"]["sequenceIndex"],
              "currentIndex": current["metadata"]["sequenceIndex"], "historyValid": history,
              "sourceRevisionChanged": previous["metadata"]["sourceRevision"] != current["metadata"]["sourceRevision"]}
    if not history:
        record["metricsUnavailableReason"] = "Actual history reset; retained, not accepted as stable motion"
        return record
    w, h, ow, oh = b["inputWidth"], b["inputHeight"], b["outputWidth"], b["outputHeight"]
    xx, yy = np.meshgrid(np.arange(2, ow - 2, 4, dtype=np.float32), np.arange(2, oh - 2, 4, dtype=np.float32))
    # Projection is T(+2*jitter/inputExtent)*P; SR output is unjittered.
    ix, iy = (xx + .5) * (w / ow) - .5 + f[268], (yy + .5) * (h / oh) - .5 + f[269]
    ca, pa = current["buffers"], previous["buffers"]
    motion = sample(ca[5], ix, iy)
    px, py = xx + motion[..., 0] * (ow / w), yy + motion[..., 1] * (oh / h)
    pix, piy = (px + .5) * (w / ow) - .5 + pf[268], (py + .5) * (h / oh) - .5 + pf[269]
    bounds = (ix >= 0) & (iy >= 0) & (ix < w - 1) & (iy < h - 1) & (pix >= 0) & (piy >= 0) & (pix < w - 1) & (piy < h - 1)
    bounds &= (px >= 0) & (py >= 0) & (px < ow - 1) & (py < oh - 1)
    sky = bounds & (depth_max(ca[4], ix, iy) <= 1e-7) & (depth_max(pa[4], pix, piy) <= 1e-7)
    cg, pg = sample(ca[3], ix, iy), sample(pa[3], pix, piy)
    opacity, previous_opacity = cg[..., 1], pg[..., 1]
    input_current, input_previous = sample(ca[0], ix, iy), sample(pa[0], pix, piy)
    output_current, output_previous = ca[1][yy.astype(np.int32), xx.astype(np.int32)].astype(np.float32), sample(pa[1], px, py)
    groups = {"opaqueCloud": sky & (opacity >= .95) & (previous_opacity >= .95),
              "partialCloud": sky & (opacity >= .02) & (opacity < .95) & (previous_opacity >= .02),
              "thresholdBand": sky & (opacity >= .4) & (opacity <= .6) & (previous_opacity >= .02),
              "clearSky": sky & (opacity < .02) & (previous_opacity < .02)}
    record["pureSkySamples"] = int(np.count_nonzero(sky))
    record["opacityThresholdCrossings"] = int(np.count_nonzero(sky & ((opacity >= .5) != (previous_opacity >= .5))))
    record["selectedGuideChanges"] = int(np.count_nonzero(sky & ((cg[..., 3] >= .5) != (pg[..., 3] >= .5))))
    record["groups"] = {name: {"input": stats(input_current, input_previous, mask),
                               "reconstructed": stats(output_current, output_previous, mask)} for name, mask in groups.items()}
    record["displayExposureEv"] = float(ca[6][0, 0, 0])
    return record


def analyze(root, out):
    manifest = json.loads((root / "cloud-frame-sequence.json").read_text())
    records = manifest["frames"]
    if manifest["count"] != 24 or len(records) != 24:
        raise ValueError("Expected all 24 frames")
    ids = [frame["renderedWorldFrame"] for frame in records]
    if any(b != a + 1 for a, b in zip(ids, ids[1:])):
        raise ValueError("Nonadjacent world frames")
    pairs, images, hashes = [], [], []
    previous = None
    for index, metadata in enumerate(records):
        current = read_capture(root, index, metadata)
        if previous is not None:
            pairs.append(pair_metrics(previous, current))
        display = Image.fromarray(np.asarray(current["buffers"][2])[::-1].copy(), "RGBA")
        display.save(out / f"display-{index:03d}.png")
        display.thumbnail((1280, 720), Image.Resampling.LANCZOS)
        images.append(display.copy())
        for path in sorted(root.glob(f"cloud-sequence-{index:03d}*")):
            hashes.append({"path": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        previous = current
    # Lossless animated PNG avoids per-frame GIF palettes adding artificial flicker.
    images[0].save(out / "cloud-sequence-30pathfps.png", save_all=True, append_images=images[1:], duration=1000 / 30, loop=0)
    contact = Image.new("RGB", (1920, 1140), "#16191d")
    for position, index in enumerate([0, 7, 15, 23]):
        thumbnail = images[index].convert("RGB"); thumbnail.thumbnail((960, 540), Image.Resampling.LANCZOS)
        x, y = (position % 2) * 960, (position // 2) * 570
        contact.paste(thumbnail, (x, y + 30))
        ImageDraw.Draw(contact).text((x + 10, y + 8), f"sequence {index}: path {records[index]['pathSeconds']:.4f}s", fill="white")
    contact.save(out / "cloud-sequence-contact.png")
    report = {"captureIntegrityVerified": True, "frames": 24, "adjacentPairs": 23,
              "allPairsHistoryValid": all(pair["historyValid"] for pair in pairs),
              "guideOption": records[0]["options"]["CLOUD_RECONSTRUCTION_GUIDES"],
              "projectionJitterRelation": "Input sample = unjittered display sample scaled to input + current jitter; motion is jitter-free previous-minus-current input pixels",
              "metric": "Absolute and symmetric relative linear-HDR luminance/RGB deltas after warping the previous frame with actual submitted motion; relative denominator .01 + .5*(abs(current luma)+abs(previous luma))",
              "sampling": "Display grid every 4 pixels; require all four base-depth neighbors in both frames to be clear sky; opaque>=.95, partial [.02,.95), threshold [.4,.6], clear<.02",
              "animation": "Actual display readbacks with row order reversed, scaled to 1280x720; lossless APNG replay at 30 path fps, not measured game FPS",
              "scope": "Proxy-warped temporal residuals contain integration/sampling/view/volume-proxy differences. No high-sample ground truth; not a ghosting oracle, performance benchmark, cinematic or temporal visual acceptance.",
              "pairs": pairs}
    (out / "analysis.json").write_text(json.dumps(report, indent=2) + "\n")
    (out / "capture-files.sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        result = analyze(args.capture, args.output)
        print(json.dumps({key: result[key] for key in ["captureIntegrityVerified", "frames", "adjacentPairs", "allPairsHistoryValid", "guideOption"]}))
    except Exception as error:
        (args.output / "failure.json").write_text(json.dumps({"captureIntegrityVerified": False, "failure": repr(error)}, indent=2) + "\n")
        raise
