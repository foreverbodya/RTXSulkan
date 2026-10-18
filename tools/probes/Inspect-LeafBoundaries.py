"""Inspect actual captured leaf triangle topology, independently of GPU queries.

This deliberately ignores alpha and material throughput. It cannot diagnose
light transport or serve as a rendered-image acceptance test.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np


def leaf_triangles(geometry):
    vertices, identities = [], []
    for mesh_index, mesh in enumerate(geometry['meshes']):
        positions = np.asarray(mesh['positions'], dtype=np.float64).reshape(-1, 3)
        materials = np.asarray(mesh['materials'])
        for triangle_index, triangle in enumerate(np.asarray(mesh['indices']).reshape(-1, 3)):
            if materials[triangle[0]] == 5:
                vertices.append(positions[triangle])
                identities.append((mesh_index, triangle_index // 2))
    if not vertices:
        raise ValueError('No source leaf triangles captured')
    return np.asarray(vertices), identities


def intersections(triangles, identities, origin, direction, maximum):
    a = triangles[:, 0]
    e1 = triangles[:, 1] - a
    e2 = triangles[:, 2] - a
    cross = np.cross(np.broadcast_to(direction, e2.shape), e2)
    determinant = np.einsum('ij,ij->i', e1, cross)
    nonparallel = np.abs(determinant) > 1e-12
    inverse = np.divide(1., determinant, out=np.zeros_like(determinant), where=nonparallel)
    offset = origin - a
    u = np.einsum('ij,ij->i', offset, cross) * inverse
    q = np.cross(offset, e1)
    v = np.einsum('j,ij->i', direction, q) * inverse
    distance = np.einsum('ij,ij->i', e2, q) * inverse
    valid = nonparallel & (u >= 0) & (v >= 0) & (u+v <= 1) & (distance > .002) & (distance <= maximum)
    faces = {}
    for index in np.flatnonzero(valid):
        identity = identities[index]
        value = {'distance': float(distance[index]), 'inward': bool(determinant[index] > 0),
                 'mesh': identity[0], 'quad': identity[1]}
        if identity in faces and faces[identity]['inward'] != value['inward']:
            raise ValueError('One captured quad has contradictory orientation')
        faces[identity] = value
    return sorted(faces.values(), key=lambda hit: (hit['distance'], hit['mesh'], hit['quad']))


def audit(path):
    raw = path.read_bytes()
    geometry = json.loads(raw)
    triangles, identities = leaf_triangles(geometry)
    groups = []
    fixtures = [
        ('tree_vertical', [[9.143+i*.37, 103., 7.211+j*.34] for i in range(8) for j in range(8)], [0., -1., 0.], 6.),
        ('leaf_wall_horizontal', [[18.13+i*.47, 102.19+j*.43, 23.] for i in range(8) for j in range(8)], [0., 0., 1.], 3.),
    ]
    for name, origins, direction, maximum in fixtures:
        rows, counts = [], Counter()
        for origin in origins:
            faces = intersections(triangles, identities, np.asarray(origin), np.asarray(direction), maximum)
            inward = sum(face['inward'] for face in faces)
            outward = len(faces)-inward
            counts[f'{inward} inward / {outward} outward'] += 1
            rows.append({'origin': origin, 'direction': direction, 'faces': faces})
        if not any(row['faces'] for row in rows):
            raise ValueError(f'Fixture {name} missing from source geometry')
        groups.append({'fixture': name, 'rays': rows, 'orientationHistogram': dict(counts)})
    return {'sourceGeometrySha256': hashlib.sha256(raw).hexdigest(),
            'leafTriangles': len(triangles), 'groups': groups,
            'scope': 'Fixed rays through captured synthetic tree/wall source triangles. Alpha ignored; all geometric faces counted once per source quad. No GPU, transport, photometric or visual acceptance claim.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('geometry', type=Path)
    parser.add_argument('output', type=Path)
    arguments = parser.parse_args()
    if arguments.output.exists():
        raise FileExistsError(arguments.output)
    result = audit(arguments.geometry)
    arguments.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({group['fixture']: group['orientationHistogram'] for group in result['groups']}))
