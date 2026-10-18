"""Replay a saved water-motion source miss without a game/GPU. Diagnostic only."""
import json
import math
import struct
import sys
from pathlib import Path


def add(a, b): return [a[i] + b[i] for i in range(3)]
def sub(a, b): return [a[i] - b[i] for i in range(3)]
def scale(a, s): return [v * s for v in a]
def dot(a, b): return sum(x * y for x, y in zip(a, b))
def cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def mul(m, v): return [sum(m[c*4+r] * v[c] for c in range(4)) for r in range(4)]
def f32(v): return struct.unpack("f", struct.pack("f", v))[0]


def segment_distance(p, a, b):
    d = sub(b+[0], a+[0])[:2]
    length2 = dot(d, d)
    t = max(0, min(1, dot(sub(p+[0], a+[0])[:2], d) / length2)) if length2 else 0
    return math.hypot(p[0]-a[0]-t*d[0], p[1]-a[1]-t*d[1])


def barycentric_2d(p, triangle):
    a, b, c = triangle
    ex, ey, fx, fy = b[0]-a[0], b[1]-a[1], c[0]-a[0], c[1]-a[1]
    determinant = ex*fy-ey*fx
    if not determinant:
        return None
    sx, sy = p[0]-a[0], p[1]-a[1]
    u, v = (sx*fy-sy*fx)/determinant, (ex*sy-ey*sx)/determinant
    return [1-u-v, u, v]


case_path, output = map(Path, sys.argv[1:3])
precision_bits = int(sys.argv[3]) if len(sys.argv) > 3 else None
if precision_bits is not None and not 1 <= precision_bits <= 24:
    raise ValueError("Supply actual hardware subPixelPrecisionBits in [1,24]")
case = json.loads(case_path.read_text(encoding="utf-8-sig"))
f = [f32(v) for v in case["frameFloats"]]
ip, iv, projection, view = f[32:48], f[48:64], f[:16], f[16:32]
w, h, x, y = (case[k] for k in ("width", "height", "x", "y"))
origin = case["camera"]
eye = mul(ip, [(x+.5)/w*2-1, (y+.5)/h*2-1, 1, 1])
world = mul(iv, [eye[i]/eye[3] for i in range(3)]+[0])[:3]
direction = scale(world, 1 / math.sqrt(dot(world, world)))
candidates = []
for index, triangle in enumerate(case["source"]["sourceTrianglesOriginEdges"]):
    a, e1, e2 = triangle[:3], triangle[3:6], triangle[6:9]
    hv = cross(direction, e2)
    determinant = dot(e1, hv)
    if determinant <= 1e-12:
        continue
    s = sub(origin, a)
    u = dot(s, hv) / determinant
    q = cross(s, e1)
    v, distance = dot(direction, q)/determinant, dot(e2, q)/determinant
    if distance <= 0:
        continue
    screen = []
    for vertex in (a, add(a, e1), add(a, e2)):
        clip = mul(projection, mul(view, sub(vertex, origin)+[1]))
        if clip[3] <= 1e-8:
            screen = []
            break
        screen.append([(clip[0]/clip[3]*.5+.5)*w, (clip[1]/clip[3]*.5+.5)*h])
    edge_distance = min(segment_distance([x+.5, y+.5], screen[i], screen[(i+1)%3]) for i in range(3)) if screen else None
    rounding = None
    if precision_bits is not None and screen:
        quantum = 2.0**-precision_bits
        rounded = [[round(value/quantum)*quantum for value in vertex] for vertex in screen]
        bary = barycentric_2d([x+.5, y+.5], rounded)
        rounding = {"subPixelPrecisionBits": precision_bits, "quantumPixels": quantum,
                    "nearestRoundedVertices": rounded, "barycentrics": bary,
                    "coveredIgnoringEdgeTieRule": bary is not None and min(bary) >= 0}
    candidates.append({"triangleIndex": index, "u": u, "v": v, "distance": distance,
                       "minimumBarycentric": min(u, v, 1-u-v), "projectedVertices": screen,
                       "distanceToProjectedEdgesPixels": edge_distance, "fixedPointRoundingDiagnostic": rounding})
candidates.sort(key=lambda c: c["minimumBarycentric"], reverse=True)
hits = [c for c in candidates if c["minimumBarycentric"] >= 0]
report = {"sourceCase": str(case_path.resolve()), "pixel": [x, y], "direction": direction,
          "strictSourceHits": len(hits), "nearestStrictHit": min(hits, key=lambda c: c["distance"]) if hits else None,
          "closestBarycentricCandidates": candidates[:8],
          "scope": "Independent double replay from exact float32 frame values and saved source triangles. Optional nearest vertex rounding is diagnostic, not proof of GPU rounding or a complete raster/clip/edge-tie emulator. No hit, motion or coverage acceptance is changed."}
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
