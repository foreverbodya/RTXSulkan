"""Collect real scene screenshots and GPU timings for cloud art stages."""
import argparse,csv,json
from pathlib import Path
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('--day',required=True);p.add_argument('--sunset',required=True);p.add_argument('--output',required=True)
p.add_argument('--motion-day');p.add_argument('--motion-sunset');a=p.parse_args()
out=Path(a.output);out.mkdir(parents=True,exist_ok=True);rows=[];runs={}
for label,name in [('day',a.day),('sunset',a.sunset)]:
 root=Path(name);runs[label]=json.loads((root/'result.json').read_text());images=[]
 for stage in range(4):
  phase=f'cloud{stage}-static';f=root/'evidence';j=json.loads((f/f'profile-{phase}.json').read_text());t=json.loads((f/f'gpu-pass-timings-profile-{phase}.json').read_text());g=t['passes']
  cloud=sum(v['mean'] for k,v in g.items() if k.startswith('cloud.') and 'diagnostic' not in k)
  row={'scene':label,'stage':stage,'gpuMs':g['world.total']['mean'],'wallMs':j['wallMs']['mean'],'fps':1000/j['wallMs']['mean'],'cloudPassesMs':cloud,'cloudViewMs':g['cloud.view']['mean'],'cloudSourceMs':g['cloud.source.light']['mean'],'cloudShadowMs':g['cloud.shadow']['mean'],'cloudCompositeMs':g['cloud.composite']['mean'],'cloudHistoryMs':g['cloud.temporal']['mean'],'presentMs':g['composition.present']['mean'],'geometrySha256':j['geometryStartSha256'],'image':str(f/f'profile-{phase}.png'),'passes':{k:{n:v for n,v in data.items() if n!='milliseconds'} for k,data in g.items()}}
  rows.append(row);images.append(Image.open(row['image']).convert('RGB'))
  images[-1].save(out/f'{label}-step-{stage}.png')
 sheet=Image.new('RGB',(1280,4*384),(20,20,20));draw=ImageDraw.Draw(sheet)
 for stage,im in enumerate(images):
  r=rows[-4+stage];sheet.paste(im.resize((640,360)),(0,stage*384+24));sheet.paste(im.crop((600,20,1880,740)).resize((640,360)),(640,stage*384+24));draw.text((8,stage*384+5),f'{label} step {stage} / GPU {r["gpuMs"]:.2f} ms / cloud passes {r["cloudPassesMs"]:.2f} ms',fill='white')
 sheet.save(out/f'{label}-all-steps.png')
 # Side by side full frames, plus larger matched cloud crop beneath.
 pair=Image.new('RGB',(1280,768),(20,20,20));d=ImageDraw.Draw(pair)
 for col,stage in enumerate([0,3]):
  pair.paste(images[stage].resize((640,360)),(col*640,24));pair.paste(images[stage].crop((600,20,1880,740)).resize((640,360)),(col*640,408));d.text((col*640+8,5),f'{label}: '+('BEFORE' if stage==0 else 'AFTER'),fill='white')
 pair.save(out/f'{label}-before-after.png')
with (out/'cloud-art.csv').open('w',newline='') as f:
 fields=[k for k in rows[0] if k!='passes'];w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows({k:r[k] for k in fields} for r in rows)
(out/'cloud-art.json').write_text(json.dumps({'rows':rows,'runs':runs,'sameGeometryWithinScene':{s:len({r['geometrySha256'] for r in rows if r['scene']==s})==1 for s in ['day','sunset']},'scope':'128 real frames per stage, fixed camera and frozen scene. GPU timestamps; screenshots occur after timed intervals. Cloud passes exclude QA diagnostic passes. Final presentation includes post-RR clouds; its incremental overhead must be counted separately.'},indent=2))
for r in rows:print(r['scene'],r['stage'],round(r['gpuMs'],3),round(r['cloudPassesMs'],3),round(r['presentMs'],3))
for label,name in [('day',a.motion_day),('sunset',a.motion_sunset)]:
 if not name:continue
 root=Path(name)/'evidence';reports=[json.loads((root/f'temporal-motion-{phase}'/'sequence.json').read_text()) for phase in ['reference','candidate']]
 assert reports[0]['sourceGeometrySha256']==reports[1]['sourceGeometrySha256']
 assert all(f['uniformsMatchReferenceOutsideAsIdentity'] for f in reports[1]['frames'])
 sheet=Image.new('RGB',(1280,8*384),(20,20,20));d=ImageDraw.Draw(sheet);animation=[]
 for frame in range(8):
  pair=Image.new('RGB',(1280,384),(20,20,20));pd=ImageDraw.Draw(pair)
  for col,phase in enumerate(['reference','candidate']):
   im=Image.open(root/f'temporal-motion-{phase}'/f'{frame:03d}-display.png').convert('RGB').crop((400,0,2320,1080)).resize((640,360))
   pair.paste(im,(col*640,24));pd.text((col*640+8,5),f'{label} frame {frame}: '+('BEFORE' if col==0 else 'AFTER'),fill='white')
  sheet.paste(pair,(0,frame*384));animation.append(pair)
 sheet.save(out/f'{label}-motion-eight.png')
 animation[0].save(out/f'{label}-motion.gif',save_all=True,append_images=animation[1:],duration=100,loop=0)
 (out/f'{label}-motion-validation.json').write_text(json.dumps({'sameGeometry':True,'matchedPhysicalUniforms':True,'captureFrames':8,'scope':'Adjacent prescribed camera frames, frozen weather/time. Readbacks are untimed and GIF is slowed to 100 ms/frame, not runtime FPS. Does not prove all camera speeds or cloud animation are artifact-free.','reports':reports},indent=2))
