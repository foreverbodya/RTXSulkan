"""Compare actual same-frame GPU display variants, without an aesthetic score."""
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


run, out = Path(sys.argv[1]), Path(sys.argv[2])
terminal = load(run/'result.json')
assert terminal['runtimeModsUnchanged']
if not terminal['passed']:
    assert sys.argv[3:] == ['--retained-night-failure']
    assert terminal.get('nightDisplay')
    assert set(terminal['missingMarkers']) == {
        'SULKAN_QA_VISUAL_CAPTURE_PASS phase=night must occur exactly once',
        'Cloud reconstruction missing for night'}
assert terminal['displayToneMapper'] == 3
assert not out.exists()
source = run/'evidence' if (run/'evidence').is_dir() else run
phases = ['day', 'sunset', 'night'] if terminal.get('nightDisplay') else ['day', 'sunset']
reports, hashes, geometries = {}, [], set()
for phase in phases:
    meta = load(source/f'tone-map-{phase}.json')
    assert meta['sharedSourceAndExposure'] and set(meta['modes']) == {'0','1','2','3'}
    assert meta['sourceReconstruction']['displayToneMapper'] == 3
    w, h = meta['width'], meta['height']
    assert (w, h) == (2560, 1440)
    hdr_path = source/meta['sourceHdrFile']
    hdr_bytes = hdr_path.read_bytes()
    assert hashlib.sha256(hdr_bytes).hexdigest() == meta['sourceHdrSha256']
    hdr = np.frombuffer(hdr_bytes, dtype='<f2').astype(float).reshape(h, w, 4)
    assert np.isfinite(hdr).all()
    ev_path = source/meta['sourceExposureFile']
    exposure = np.fromfile(ev_path, dtype='<f4')
    assert exposure.shape == (4,) and float(exposure[0]) == meta['displayExposureEV']
    exposed = np.maximum(hdr[:, :, :3], 0) * 2**float(exposure[0])
    luminance = np.flipud(exposed @ [.2126, .7152, .0722])
    a_path = source/meta['modes']['2']['image']
    b_path = source/meta['modes']['3']['image']
    with Image.open(a_path) as image:
        a = np.asarray(image.convert('RGB')).astype(int)
    with Image.open(b_path) as image:
        b = np.asarray(image.convert('RGB')).astype(int)
    assert a.shape == b.shape == (h, w, 3)
    highlights = luminance >= .18 + 1e-6
    shadows = (luminance > 1e-10) & (luminance < .18 - 1e-6)
    black = luminance == 0
    if np.any(highlights):
        assert np.max(np.abs(a[highlights]-b[highlights])) <= 1
    if np.any(black):
        assert np.array_equal(a[black], b[black])
    cadence_path = source/f'{phase}-cadence.json'
    cadence = load(cadence_path)
    assert cadence['poseCheckedEveryFrame'] and cadence['steadyGeometry']
    assert cadence['time'] == {'day':3000,'sunset':12600,'night':18000}[phase]
    geometries.add(cadence['geometrySha256'])
    record = dict(sourceHdrSha256=meta['sourceHdrSha256'], exposureEV=float(exposure[0]),
        unexposedMeanRgb=float(np.mean(hdr[:, :, :3])),
        unexposedMinMeanRgb=float(np.min(np.mean(hdr[:, :, :3], axis=2))),
        unexposedMaxMeanRgb=float(np.max(np.mean(hdr[:, :, :3], axis=2))),
        displayMode=3, pixels=w*h, highlightedPixels=int(highlights.sum()),
        maxHighlightCodeDifference=int(np.max(np.abs(a[highlights]-b[highlights]))) if highlights.any() else None,
        nonzeroShadowPixels=int(shadows.sum()), exactBlackPixels=int(black.sum()),
        baselineMeanEncodedShadowLuma=float(np.mean(a[shadows] @ [.2126,.7152,.0722])) if shadows.any() else None,
        candidateMeanEncodedShadowLuma=float(np.mean(b[shadows] @ [.2126,.7152,.0722])) if shadows.any() else None,
        maxGpuReferenceError=max(row['maxAbsoluteError'] for row in meta['modes'].values()))
    frame_path = source/f'tone-map-{phase}-source-frame.bin'
    paths = [source/f'tone-map-{phase}.json', hdr_path, ev_path, a_path, b_path, cadence_path]
    if frame_path.exists():
        frame = np.fromfile(frame_path, dtype='<f4')
        record['environmentSunDirection'] = frame[112:115].astype(float).tolist()
        # Runtime 0D9765... took this environment snapshot on readback completion;
        # do not claim the frame's jitter/matrices belong to the saved HDR.
        record['environmentFrameScope'] = 'Readback-completion environment snapshot; not exact HDR-frame matrix lineage.'
        if phase == 'night':
            assert frame[113] < 0
        paths.append(frame_path)
    reports[phase] = record
    for path in paths:
        hashes.append(dict(path=path.name, bytes=path.stat().st_size,
            sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
assert len(geometries) == 1
out.mkdir()
report = dict(runtimeSha256=terminal['artifactSha256'], geometrySha256=next(iter(geometries)),
    overallRunPassed=terminal['passed'], retainedMissingMarkers=terminal['missingMarkers'],
    sameFrameDisplayChecksPassed=True, phases=reports, otherModsUnchanged=True,
    candidatePromoted=False, userInstanceUpdated=False, cinematicAccepted=False, goalComplete=False,
    scope='Actual 1440p GPU display variants sharing completed HDR and exposure. Source bytes/hashes retained; highlight-code compatibility and exact-black comparisons. Explicit retained-failure mode only analyzes completed display captures and never supersedes an overall failed run. Shadow encoded-code means describe presentation, not more transport, calibrated luminance, better materials or artistic acceptance. No interior/movement acceptance. Existing solar-source Tmin failure remains open.')
(out/'analysis.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
(out/'input-files.sha256.json').write_text(json.dumps(hashes, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, indent=2))
