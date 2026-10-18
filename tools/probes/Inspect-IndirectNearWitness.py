"""Inspect one retained first-ray failure; never changes a conformance verdict."""
import hashlib
import json
from pathlib import Path
import sys
import math

import numpy as np


def hash32(value):
    value &= 0xffffffff
    value = ((value ^ (value >> 16)) * 0x7feb352d) & 0xffffffff
    value = ((value ^ (value >> 15)) * 0x846ca68b) & 0xffffffff
    return value ^ (value >> 16)


def inspect(root, pixel, output):
    if output.exists():
        raise ValueError('Use a new report path')
    stem = root / 'leaf-indirect-source'
    inputs = json.loads(Path(str(stem) + '-inputs.json').read_text(encoding='utf-8-sig'))
    files = [Path(str(stem) + '-frame.bin'), Path(str(stem) + '-geometry.json')]
    frame = np.fromfile(files[0], dtype='<f4')
    width, height = frame[104:106].astype(int)
    x, y = pixel % width, pixel // width
    buffers = {}
    for index in (0, 1, 5, 6, 7):
        path = Path(str(stem) + f'-buffer-{index}.bin')
        files.append(path)
        dtype = '<u4' if index == 0 else '<f2' if index == 1 else '<f4'
        channels = 2 if index == 0 else 4
        buffers[index] = np.fromfile(path, dtype=dtype).reshape(-1, channels)
    depth = buffers[0][pixel, 1:2].view('<f4')[0]
    n = buffers[1][pixel, :3].astype(float)
    n /= np.linalg.norm(n)
    if inputs['backLobe']:
        n = -n
    inv_projection = frame[32:48].astype(float).reshape(4, 4).T
    inv_view = frame[48:64].astype(float).reshape(4, 4).T
    eye = inv_projection @ np.array([(x + .5) / width * 2 - 1, (y + .5) / height * 2 - 1, depth, 1])
    relative = inv_view[:3, :3] @ (eye[:3] / eye[3])
    camera = frame[120:123].astype(float) + frame[124:127].astype(float)
    origin = camera + relative + n * .005
    direction = buffers[6][pixel, :3].astype(float)
    seed = int(x + y * 65537) ^ (0xa511e9b3 if inputs['backLobe'] else 0)
    if inputs['activeOptions'].get('INDIRECT_TEMPORAL') == 1:
        seed ^= hash32(int(frame.view('<u4')[283]) + 0x68bc21eb)
    u = ((hash32(seed) & 0xffffff) + .5) / 16777216
    v = ((hash32(seed ^ 0x9e3779b9) & 0xffffff) + .5) / 16777216
    tangent = np.cross([0, 0, 1] if abs(n[2]) < .999 else [1, 0, 0], n)
    tangent /= np.linalg.norm(tangent)
    cpu_direction = (tangent * (math.sqrt(u) * math.cos(2 * math.pi * v))
                     + np.cross(n, tangent) * (math.sqrt(u) * math.sin(2 * math.pi * v))
                     + n * math.sqrt(1 - u))
    cpu_direction /= np.linalg.norm(cpu_direction)
    gpu_hit = buffers[5][pixel].astype(float)
    # This is inferred from a rounded output endpoint, not an independently
    # captured ray input. It must not be used to pass source conformance.
    scene_origin = frame[272:275].astype(float)
    inferred_origin = gpu_hit[:3] + scene_origin - direction * gpu_hit[3]
    geometry = json.loads(files[1].read_text(encoding='utf-8-sig'))
    candidates = []
    for mesh_index, mesh in enumerate(geometry['meshes']):
        positions = np.asarray(mesh['positions'], dtype=float).reshape(-1, 3)
        indices = np.asarray(mesh['indices']).reshape(-1, 3)
        triangles = positions[indices]
        e1 = triangles[:, 1] - triangles[:, 0]
        e2 = triangles[:, 2] - triangles[:, 0]
        for label, o, d in [('idealDoubleOrigin_cpuDirection', origin, cpu_direction),
                             ('idealDoubleOrigin_gpuDirection', origin, direction),
                             ('inferredGpuOrigin_gpuDirection', inferred_origin, direction)]:
            h = np.cross(d, e2)
            determinant = np.einsum('ij,ij->i', e1, h)
            valid_det = np.abs(determinant) > 1e-12
            safe_det = np.where(valid_det, determinant, 1)
            s = o - triangles[:, 0]
            u = np.einsum('ij,ij->i', s, h) / safe_det
            q = np.cross(s, e1)
            v = q @ d / safe_det
            t = np.einsum('ij,ij->i', e2, q) / safe_det
            mask = valid_det & (u >= 0) & (v >= 0) & (u + v <= 1) & (t >= 0) & (t < .01)
            for i in np.flatnonzero(mask):
                candidates.append(dict(ray=label, mesh=mesh_index, triangle=int(i),
                    material=mesh['materials'][indices[i, 0]], distance=float(t[i]),
                    distanceMinusTmin=float(t[i] - .002), barycentrics=[float(u[i]), float(v[i])],
                    vertices=triangles[i].tolist(), cutout=mesh['cutout']))
    report = dict(pixel=pixel, xy=[int(x), int(y)], extent=[int(width), int(height)],
        camera=camera.tolist(), normal=n.tolist(), idealDoubleOrigin=origin.tolist(),
        capturedGpuDirection=direction.tolist(), capturedGpuEndpoint=gpu_hit.tolist(),
        originalCpuDirection=cpu_direction.tolist(), maxDirectionDifference=float(np.max(np.abs(direction-cpu_direction))),
        sceneOrigin=scene_origin.tolist(), inferredGpuOrigin=inferred_origin.tolist(),
        inferredMinusIdeal=(inferred_origin - origin).tolist(), candidates=candidates,
        sourceSha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
        scope='Single retained ray, source triangle intersections before alpha. Original CPU direction and GPU direction with ideal double origin, plus origin inferred from rounded GPU endpoint. No actual origin input readback, no changed tolerance and no revised conformance verdict.')
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('sourceSha256',)}, indent=2))


if __name__ == '__main__':
    inspect(Path(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3]))
