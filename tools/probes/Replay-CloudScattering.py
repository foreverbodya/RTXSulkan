"""Replay captured GPU uniform-slab outputs with an independent scalar formula.

No shader execution or game libraries. A failed capture remains failed if its
declared parameter grid differs, even when its radiance math is within bounds.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct


def hg(cosine, g):
    return (1 - g * g) / (4 * math.pi * (1 + g * g - 2 * g * cosine) ** 1.5)


def analytic(column, view, sun, cosine, order):
    b = 2.0 ** -order
    phase = 0.8 * hg(cosine, 0.75 * b) + 0.2 * hg(cosine, -0.2 * b)
    front, back = -column * b / sun, -column * b / view
    if abs(view - sun) < 1e-12:
        return phase * (-back) * math.exp(front)
    # Difference of exponentials, expressed without subtracting nearly equal values.
    return phase * math.exp(front) * -math.expm1(back - front) / (1 - view / sun)


def sinhc(x):
    return 1 + x*x/6 if abs(x) < 1e-8 else math.sinh(x)/x


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--phase", default="day")
    parser.add_argument("--legacy-grid", action="store_true")
    args = parser.parse_args()
    stem = f"cloud-scattering-{args.phase}"
    radiance = (args.directory / f"{stem}-radiance.bin").read_bytes()
    parameters = (args.directory / f"{stem}-parameters.bin").read_bytes()
    if len(radiance) != 32*64*16 or len(parameters) != len(radiance):
        raise ValueError("Unexpected raw buffer extent")
    grid_failures, transport_failures = [], []
    maximum, maximum_t, maximum_bias = 0.0, 0.0, 0.0
    columns = [0, 1/4096, 1/1024, 1/64, 1/16, .25, 1, 4]
    comparisons = 0
    for index in range(32*64):
        x, y = index % 32, index // 32
        p = struct.unpack_from("<4f", parameters, index*16)
        values = struct.unpack_from("<4f", radiance, index*16)
        view = .25 + .75*(x//8 % 4)/3
        expected_grid = [
            (0 if x % 8 == 0 else 2 ** (-12 + 14*(x % 8 - 1)/6)) if args.legacy_grid else columns[x % 8],
            view, view if y % 8 == 0 else .2 + .8*(y % 8)/7, -1 + 2*(y//8)/7,
        ]
        for channel in range(4):
            if not math.isfinite(p[channel]) or abs(p[channel] - expected_grid[channel]) > 1e-6:
                grid_failures.append({"pixel": index, "channel": channel,
                                      "expected": expected_grid[channel], "actual": p[channel]})
        if not all(map(math.isfinite, p)):
            raise ValueError("Nonfinite slab input")
        column, view, sun, cosine = p
        for order in range(3):
            expected = analytic(column, view, sun, cosine, order)
            b = 2.0 ** -order
            a, c = column*b/view/256, column*b/sun/256
            bias = abs(sinhc(a/2)/sinhc((a-c)/2) - 1)
            error = abs(values[order] - expected)
            bound = bias*expected + 2e-6 + 3e-5*expected
            maximum = max(maximum, error)
            maximum_bias = max(maximum_bias, bias)
            comparisons += 1
            if not math.isfinite(values[order]) or values[order] < 0 or error > bound:
                transport_failures.append({"pixel": index, "order": order, "expected": expected,
                                           "actual": values[order], "error": error, "bound": bound})
        error_t = abs(values[3] - math.exp(-column/view))
        maximum_t = max(maximum_t, error_t)
        if not math.isfinite(values[3]) or not 0 <= values[3] <= 1 or error_t > 256*2**-23:
            transport_failures.append({"pixel": index, "transmissionError": error_t})
    report = {
        "passed": not grid_failures and not transport_failures,
        "parameterGridPassed": not grid_failures, "transportMathPassed": not transport_failures,
        "legacyGrid": args.legacy_grid, "orderComparisons": comparisons,
        "gridFailureCount": len(grid_failures), "gridFailures": grid_failures,
        "transportFailures": transport_failures, "maxRadianceError": maximum,
        "maxTransmissionError": maximum_t, "maxAnalyticMidpointRelativeBias": maximum_bias,
        "radianceSha256": hashlib.sha256(radiance).hexdigest(),
        "parametersSha256": hashlib.sha256(parameters).hexdigest(),
        "scope": "Closed-form uniform slab replay. Not heterogeneous-cloud convergence, full multiple scattering or visual acceptance.",
    }
    if args.output.exists():
        raise FileExistsError("Never overwrite earlier replay results")
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in ("gridFailures", "transportFailures")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
