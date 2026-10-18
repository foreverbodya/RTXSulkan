"""Bounded cloud source approximation versus actual original GPU integration."""
from pathlib import Path
import argparse,json,hashlib
import numpy as np
from PIL import Image,ImageDraw,ImageFont
p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('out',type=Path);a=p.parse_args()
def load(path):return json.loads(path.read_text(encoding='utf-8-sig'))
result=load(a.run/'result.json')
assert result['passed'] and result['runtimeModsUnchanged']
source=a.run/'evidence' if (a.run/'evidence').is_dir() else a.run
a.out.mkdir(exist_ok=False,parents=True)
rows=[];files=set();geometries=set();poses=[];options=None
sheet=Image.new('RGB',(1920,1140),(22,22,22));draw=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',20)
for row,phase in enumerate(['day','sunset']):
    captures=[f'{phase}-cloud-source-{mode}-trial-{trial}' for trial,mode in enumerate([0,1,0],1)]
    env=[];cost=[]
    for mode,name in zip([0,1,0],captures):
        cadence=load(source/f'{name}-cadence.json');timing=load(source/f'gpu-pass-timings-{name}.json')
        assert cadence['serverFrozen'] and cadence['poseCheckedEveryFrame'] and cadence['steadyGeometry']
        assert cadence['comparisonOption']=='CLOUD_SOURCE_CACHE' and cadence['comparisonValue']==mode
        assert cadence['measuredSeconds']>=20
        geometries.add(cadence['geometrySha256']);pose=cadence['actualPoseEnd']
        poses.append({k:pose[k] for k in ['eye','forward','directionBasis']})
        actual=dict(cadence['activeOptions']);assert actual.pop('CLOUD_SOURCE_CACHE')==mode
        if options is None:options=actual
        assert options==actual
        path=source/f'cloud-preview-{name}-buffer-6.bin';data=np.fromfile(path,dtype='<f2').reshape(128,256,4)
        assert np.isfinite(data).all() and (data>=0).all() and (data[:,:,3]<=1).all()
        env.append(data.astype(np.float64));files.update([path,source/f'{name}-cadence.json',source/f'gpu-pass-timings-{name}.json',source/f'cloud-preview-{name}-frame.bin'])
        passes=timing['passes'];cloud={k:passes[k]['mean'] for k in ['cloud.source.cache','cloud.view','cloud.environment','cloud.composite']}
        cost.append(dict(capture=name,mode=mode,wallMeanMs=cadence['meanFrameMs'],gpuCloudPassesMs=cloud,gpuCloudTotalMs=sum(cloud.values())))
    old,new,repeat=env
    delta=np.abs(new[:,:,:3]-old[:,:,:3]);mask=old[:,:,3]<.99
    rgbref=old[:,:,:3];relative=np.mean(delta,axis=2)/np.maximum(np.mean(rgbref,axis=2),1e-5)
    values=dict(phase=phase,cost=cost,referenceRepeatMaxAbsDifference=float(np.max(np.abs(repeat-old))),
        alphaByteEqualPixels=int(np.count_nonzero(new[:,:,3]==old[:,:,3])),pixels=128*256,
        alphaMaxAbsDifference=float(np.max(np.abs(new[:,:,3]-old[:,:,3]))),
        rgbRelativeL1=float(delta.sum()/np.maximum(rgbref.sum(),1e-12)),
        cloudyRgbRelativeL1=float(delta[mask].sum()/np.maximum(rgbref[mask].sum(),1e-12)),
        cloudyRelativeErrorPercentiles=dict(zip(['p50','p90','p99','max'],map(float,np.percentile(relative[mask],[50,90,99,100])))),
        rgbMaxAbsDifference=float(delta.max()),scope='Actual cached source approximation vs original GPU nested integrals in a fixed-direction cloud environment. Not converged continuous transport, view-ray comparison, motion or aesthetic acceptance.')
    vertical=(np.arange(128)+.5)/64-1;mu=np.sign(vertical)*vertical**2
    bins=[]
    for lo,hi in [(0,.05),(.05,.15),(.15,.4),(.4,1)]:
        selected=((mu>=lo)&(mu<hi))[:,None]&mask
        bins.append(dict(minCosElevation=lo,maxCosElevation=hi,pixels=int(selected.sum()),
            relativeL1=float(delta[selected].sum()/max(rgbref[selected].sum(),1e-12))))
    values['cloudyDirectionBins']=bins
    rows.append(values)
    for col,name in enumerate(captures[:2]):
        path=source/f'natural-{name}.png';files.add(path)
        with Image.open(path) as im:sheet.paste(im.convert('RGB').resize((960,540),Image.Resampling.LANCZOS),(col*960,row*570+30))
        draw.text((col*960+10,row*570+5),f'{phase} | CLOUD_SOURCE_CACHE={col}',font=font,fill='white')
assert len(geometries)==1 and all(pose==poses[0] for pose in poses)
sheet.save(a.out/'actual-display-comparison.png')
report=dict(hostSha256=result['artifactSha256'],geometrySha256=next(iter(geometries)),actualPoseMatched=True,unrelatedOptionsMatched=True,
    phases=rows,passedExecutionGates=True,approximationAccepted=False,motionAccepted=False,cinematicAccepted=False,userInstanceUpdated=False,
    scope='Repeated same-world source-cache comparison. HDR differences are measured, not declared aesthetic tolerance passes. Exact extinction/geometry code retained; overall image quality and speed remain unfinished.')
(a.out/'analysis.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
manifest=[dict(path=path.name,sha256=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()) for path in sorted(files)]
(a.out/'capture-files.sha256.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))
