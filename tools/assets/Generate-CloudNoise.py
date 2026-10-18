"""Build a periodic 128^3 cellular cloud field as an RGBA8 slice atlas.

Nearest-cell structure adapted from Cells in sebh/TileableVolumeNoise,
commit 1f879a26b89aaa4b5a7f112f14ec149d08057661 (MIT, Sebastien Hillaire).
See examples/ray-lighting/LICENSES/TileableVolumeNoise-MIT.txt.
Local changes: integer hashes, independent XYZ feature offsets, explicit UNORM
quantization, a 2D slice atlas, and independent scalar verification. No GLM or
LibTarga dependency. The committed PNG is usable without this build-time tool.
"""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

SIZE = 128
SEEDS = (0x68BC21EB, 0x02E5BE93, 0x967A889B)
ROOT = Path(__file__).resolve().parents[2]


def mix32(value):
    value = np.asarray(value, dtype=np.uint32)
    value = (value ^ (value >> np.uint32(16))) * np.uint32(0x7FEB352D)
    value = (value ^ (value >> np.uint32(15))) * np.uint32(0x846CA68B)
    return value ^ (value >> np.uint32(16))


def lattice_hash(x, y, z, period):
    return ((np.asarray(x % period, dtype=np.uint32) * np.uint32(0x8DA6B343))
            ^ (np.asarray(y % period, dtype=np.uint32) * np.uint32(0xD8163841))
            ^ (np.asarray(z % period, dtype=np.uint32) * np.uint32(0xCB1AB31F)))


def unit(value):
    return ((value & np.uint32(0xFFFFFF)).astype(np.float64) + .5) / 16777216


def coordinates(cells):
    q = (np.arange(SIZE, dtype=np.float64) + .5) * cells / SIZE
    return q[None, None, :], q[None, :, None], q[:, None, None]


def cellular(cells):
    x, y, z = coordinates(cells)
    ix, iy, iz = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    nearest = np.ones((SIZE, SIZE, SIZE), dtype=np.float64)
    for dz in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                h = lattice_hash(ix + dx, iy + dy, iz + dz, cells)
                rx = x - (ix + dx + unit(mix32(h ^ np.uint32(SEEDS[0]))))
                ry = y - (iy + dy + unit(mix32(h ^ np.uint32(SEEDS[1]))))
                rz = z - (iz + dz + unit(mix32(h ^ np.uint32(SEEDS[2]))))
                nearest = np.minimum(nearest, rx * rx + ry * ry + rz * rz)
    return 1 - nearest


def value_noise(cells):
    q = coordinates(cells)
    cell = [np.floor(a).astype(int) for a in q]
    w = [(a - i) ** 2 * (3 - 2 * (a - i)) for a, i in zip(q, cell)]
    result = np.zeros((SIZE, SIZE, SIZE), dtype=np.float64)
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                h = mix32(lattice_hash(cell[0] + dx, cell[1] + dy, cell[2] + dz, cells))
                weight = (w[0] if dx else 1-w[0]) * (w[1] if dy else 1-w[1]) * (w[2] if dz else 1-w[2])
                result += unit(h) * weight
    return result


# This scalar path uses Python integers/doubles, not NumPy's lattice evaluator.
def scalar_hash(x, y, z, cells, seed=None):
    h = (((x % cells) * 0x8DA6B343) ^ ((y % cells) * 0xD8163841) ^ ((z % cells) * 0xCB1AB31F)) & 0xFFFFFFFF
    if seed is not None:
        h ^= seed
    h = ((h ^ (h >> 16)) * 0x7FEB352D) & 0xFFFFFFFF
    h = ((h ^ (h >> 15)) * 0x846CA68B) & 0xFFFFFFFF
    return (h ^ (h >> 16)) & 0xFFFFFFFF


def scalar_voxel(x, y, z, channel):
    cells = (8, 16, 32, 8)[channel]
    q = [(v+.5) * cells / SIZE for v in (x, y, z)]
    cell = [int(v // 1) for v in q]
    if channel < 3:
        distance = 1.0
        for dz in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    c = [cell[0]+dx, cell[1]+dy, cell[2]+dz]
                    offsets = [((scalar_hash(*c, cells, seed) & 0xFFFFFF)+.5)/16777216 for seed in SEEDS]
                    r = [q[a]-c[a]-offsets[a] for a in range(3)]
                    distance = min(distance, r[0]*r[0]+r[1]*r[1]+r[2]*r[2])
        value = 1-distance
    else:
        w = [(a-i)**2 * (3-2*(a-i)) for a, i in zip(q, cell)]
        value = 0.0
        for dz in (0, 1):
            for dy in (0, 1):
                for dx in (0, 1):
                    h = scalar_hash(cell[0]+dx, cell[1]+dy, cell[2]+dz, cells)
                    weight = (w[0] if dx else 1-w[0]) * (w[1] if dy else 1-w[1]) * (w[2] if dz else 1-w[2])
                    value += ((h & 0xFFFFFF)+.5)/16777216 * weight
    return int(min(255, max(0, value*255+.5)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'examples/ray-lighting/textures/cloud-cellular-128.png')
    args = parser.parse_args()
    with np.errstate(over='ignore'):
        volume = np.stack([cellular(8), cellular(16), cellular(32), value_noise(8)], axis=-1)
    volume = np.floor(np.clip(volume, 0, 1)*255+.5).astype(np.uint8)
    points = [(0, 0, 0), (127, 127, 127), (0, 127, 64), (127, 0, 64)]
    points += [((i*73+17) % 128, (i*47+61) % 128, (i*29+3) % 128) for i in range(257)]
    points = list(dict.fromkeys(points))
    for x, y, z in points:
        for channel in range(4):
            expected = scalar_voxel(x, y, z, channel)
            if int(volume[z, y, x, channel]) != expected:
                raise RuntimeError(f'Scalar mismatch at {(x,y,z,channel)}')
    atlas = volume.reshape(8, 16, SIZE, SIZE, 4).transpose(0, 2, 1, 3, 4).reshape(1024, 2048, 4)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        if not np.array_equal(np.asarray(Image.open(args.output).convert('RGBA')), atlas):
            raise RuntimeError('Existing asset differs; preserve it and choose a new output path')
    else:
        Image.fromarray(atlas).save(args.output, compress_level=9)
    if args.output.stat().st_size > 8388608:
        raise RuntimeError('PNG exceeds PackFiles file limit')
    report = {'volumeSize': [128,128,128], 'atlasSize': [2048,1024], 'tiles': [16,8],
              'channels': ['inverted clamped squared F1, 8 cells', 'inverted clamped squared F1, 16 cells',
                           'inverted clamped squared F1, 32 cells', 'periodic value noise, 8 cells'],
              'scalarChecks': len(points)*4, 'sourcePngSha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
              'rgbaPixelSha256': hashlib.sha256(atlas.tobytes()).hexdigest(),
              'sourceReference': 'sebh/TileableVolumeNoise@1f879a26b89aaa4b5a7f112f14ec149d08057661',
              'license': 'MIT nearest-cell structure; local integer features/atlas work under project GPL',
              'scope': 'Deterministic asset and selected independent scalar checks; not GPU interpolation or cinematic acceptance.'}
    args.output.with_suffix('.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
