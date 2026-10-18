"""Inspect actual HDR/exposure/display captures without a beauty PASS."""
from pathlib import Path
import argparse,json,hashlib
import numpy as np
from PIL import Image,ImageDraw,ImageFont
p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('out',type=Path)
p.add_argument('--phases',nargs='+',choices=['day','sunset','night'],default=['day','sunset','night']);a=p.parse_args()
def load(path):return json.loads(path.read_text(encoding='utf-8-sig'))
terminal=load(a.run/'result.json');source=a.run/'evidence' if (a.run/'evidence').is_dir() else a.run
assert terminal['runtimeModsUnchanged']
a.out.mkdir(exist_ok=False,parents=True);rows=[];files=[]
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',21)
sheet=Image.new('RGB',(640*len(a.phases),395),(20,20,20));draw=ImageDraw.Draw(sheet)
for i,phase in enumerate(a.phases):
    t=load(source/f'tone-map-{phase}.json');w,h=t['width'],t['height']
    hdr=np.fromfile(source/t['sourceHdrFile'],dtype='<f2').reshape(h,w,4).astype(np.float32)
    assert np.isfinite(hdr).all()
    rgb=hdr[:,:,:3].astype(np.float64);pixelMean=rgb.mean(axis=2)
    frame=np.fromfile(source/f'tone-map-{phase}-source-frame.bin',dtype='<f4')
    gain=2**t['displayExposureEV'];low,high=float(pixelMean.min()),float(pixelMean.max());mean=float(pixelMean.mean())
    row=dict(phase=phase,exposureEV=t['displayExposureEV'],sunDirection=frame[112:115].tolist(),moonDirection=frame[116:119].tolist(),moonPhaseIndex=float(frame[119]),
        minRawMeanRgb=low,maxRawMeanRgb=high,rawMeanRgb=mean,exposedMeanRgb=mean*gain,exposedMeanRgbRange=(high-low)*gain,
        negativeRawChannels=int(np.count_nonzero(rgb<0)),legacyUnexposedBrightnessGatePassed=high-low>=.05 and mean>=.005,
        capturedExposureBrightnessGatePassed=(high-low)*gain>=.05 and mean*gain>=.005,
        sharedToneMapHdrSha256=t['sourceHdrSha256'])
    exp=source/f'{phase}-exposure.json'
    if exp.exists():row['meteringOracle']=load(exp)
    cadence=load(source/f'{phase}-cadence.json');row['wallMeanMs']=cadence['meanFrameMs'];row['geometrySha256']=cadence['geometrySha256']
    if (source/f'{phase}-dlss.json').exists():row['dlssReport']=load(source/f'{phase}-dlss.json')
    rows.append(row)
    display=source/f'tone-map-{phase}-agx-shadow-contrast.png'
    with Image.open(display) as im:sheet.paste(im.convert('RGB').resize((640,360),Image.Resampling.LANCZOS),(640*i,35))
    draw.text((640*i+10,6),f'{phase} | actual GPU display | EV {t["displayExposureEV"]:.2f}',font=font,fill='white')
    for name in [t['sourceHdrFile'],t['sourceExposureFile'],f'tone-map-{phase}-source-frame.bin',f'tone-map-{phase}.json',display.name,f'{phase}-cadence.json']:
        path=source/name;files.append(dict(path=name,sha256=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()))
sheet.save(a.out/('actual-'+'-'.join(a.phases)+'.png'))
report=dict(overallRunPassed=terminal['passed'],runnerInterrupted=terminal.get('runnerInterrupted',False),hostSha256=terminal['artifactSha256'],phases=rows,
    scope='Actual reconstructed HDR and same-source GPU display variants, engine celestial direction, captured EV and old/new brightness metrics. No brightness-domain metric is a beauty, physical photometry, motion or first-person acceptance.',
    nightAccepted=False,cinematicAccepted=False,playablePreview=False,userInstanceUpdated=False,goalComplete=False)
(a.out/'analysis.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
(a.out/'capture-files.sha256.json').write_text(json.dumps(files,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(overallRunPassed=terminal['passed'],phases=[{k:r[k] for k in ['phase','exposureEV','rawMeanRgb','exposedMeanRgb','legacyUnexposedBrightnessGatePassed','capturedExposureBrightnessGatePassed','wallMeanMs']} for r in rows])))
