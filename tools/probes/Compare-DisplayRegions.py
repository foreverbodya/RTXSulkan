"""Describe fixed screen regions in two captures; no visual acceptance decision.

Usage: python Compare-DisplayRegions.py before.png after.png output.json
Requires Pillow and NumPy. Images are read without modification.
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def measure(path):
    with Image.open(path) as image:
        rgb = np.asarray(image.convert("RGB"), dtype=np.float64) / 255
    h, w, _ = rgb.shape
    regions = {"whole": (0, 0, 1, 1), "central_shadow_roi": (.32, .50, .57, .75)}
    result = {}
    for name, rect in regions.items():
        x0, y0, x1, y1 = (int(rect[i] * (w if i % 2 == 0 else h)) for i in range(4))
        region = rgb[y0:y1, x0:x1]
        linear = np.where(region <= .04045, region / 12.92, ((region + .055) / 1.055) ** 2.4)
        luminance = linear @ np.array([.2126, .7152, .0722])
        display_luma = region @ np.array([.2126, .7152, .0722])
        result[name] = {"pixelRect": [x0, y0, x1, y1], "pixels": int(luminance.size),
                        "meanLinearDisplayLuminance": float(luminance.mean()),
                        "medianLinearDisplayLuminance": float(np.median(luminance)),
                        "p10LinearDisplayLuminance": float(np.quantile(luminance, .1)),
                        "p90LinearDisplayLuminance": float(np.quantile(luminance, .9)),
                        "fractionDisplayLumaBelow002": float((display_luma < .02).mean())}
    return {"path": str(Path(path).resolve()), "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
            "width": w, "height": h, "regions": result}


before, after, output = sys.argv[1:]
report = {"before": measure(before), "after": measure(after),
          "scope": "Fixed display-space ROI only; no geometry correspondence, radiometric calibration, temporal stability, equal exposure or quality acceptance claim. Scene updates may differ."}
if (report["before"]["width"], report["before"]["height"]) != (report["after"]["width"], report["after"]["height"]):
    raise ValueError("Capture extents differ")
Path(output).write_text(json.dumps(report, indent=2), encoding="utf-8")
