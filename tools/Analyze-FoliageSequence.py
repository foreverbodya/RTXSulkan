"""Reproject adjacent real captures onto first-visible static terrain. No FPS claim."""
import argparse,gzip,json
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('sequences',nargs='+');p.add_argument('--output',required=True);a=p.parse_args()
rows=[];out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
for directory in a.sequences:
 d=Path(directory); report=json.loads((d/'sequence.json').read_text()); info=report['reconstruction'];w=info['inputWidth'];h=info['inputHeight'];name=d.parent.parent.name+'-'+d.name
 def load(i,n,t,c):return np.frombuffer(gzip.decompress((d/f'{i:03}-{n}.bin.gz').read_bytes()),dtype=t).reshape(h,w,c)
 def matrix(b,offset):return np.frombuffer(b,dtype='<f4',count=16,offset=offset).reshape(4,4).T.astype(np.float64)
 def data(i):
  b=(d/f'{i:03}-frame.bin').read_bytes();return b,load(i,'gbuffer.material','<u4',2)[:,:,1],load(i,info['depthResource'],'<f4',1)[:,:,0],load(i,info['colorResource'],'<f2',4).astype(np.float32),load(i,'gbuffer.normal','<f2',4).astype(np.float32)
 def sample(image,x,y):
  hh,ww=image.shape[:2];x=np.clip(x,0,ww-1.001);y=np.clip(y,0,hh-1.001);ix=np.floor(x).astype(int);iy=np.floor(y).astype(int);fx=(x-ix)[:,None];fy=(y-iy)[:,None]
  return (image[iy,ix]*(1-fx)+image[iy,ix+1]*fx)*(1-fy)+(image[iy+1,ix]*(1-fx)+image[iy+1,ix+1]*fx)*fy
 def display(i):return np.asarray(Image.open(d/f'{i:03}-display.png').convert('RGB'),dtype=np.float32)[::-1]/255
 prev=data(0);previousDisplay=display(0);pairs=[]
 for i in range(1,8):
  cur=data(i);b,mat,depth,color,normal=cur;ys,xs=np.nonzero((mat==5)&(depth>0));
  clip=np.array([(xs+.5)*2/w-1,(ys+.5)*2/h-1,depth[ys,xs],np.ones(len(xs))]); eye=matrix(b,128)@clip;rel=matrix(b,192)[:3,:3]@(eye[:3]/eye[3]);rel+=np.frombuffer(b,dtype='<f4',count=3,offset=384)[:,None]
  pos=np.vstack([rel,np.ones(len(xs))]);pc=matrix(b,256)@matrix(b,320)@pos;u=pc[:2]/pc[3];px=np.floor((u[0]*.5+.5)*w).astype(int);py=np.floor((u[1]*.5+.5)*h).astype(int);inside=(pc[3]>0)&(px>=0)&(px<w)&(py>=0)&(py<h)
  xs=xs[inside];ys=ys[inside];px=px[inside];py=py[inside];pc=pc[:,inside];rel=rel[:,inside]
  oldDepth=prev[2][py,px];same=(prev[1][py,px]==5)&(oldDepth>0)&(np.sum(normal[ys,xs,:3]*prev[4][py,px,:3],axis=1)>.98)
  # Compare linearized surface distances to reject disocclusions and other leaf layers.
  pb=prev[0]; oldclip=np.array([(px+.5)*2/w-1,(py+.5)*2/h-1,oldDepth,np.ones(len(px))]);oldEye=matrix(pb,128)@oldclip;oldRel=matrix(pb,192)[:3,:3]@(oldEye[:3]/oldEye[3]);dist=np.linalg.norm(rel-oldRel,axis=0);same&=dist<(.02+2*np.linalg.norm(rel,axis=0)/(abs(matrix(b,0)[1,1])*h))
  v=color[ys[same],xs[same],:3];o=prev[3][py[same],px[same],:3];difference=np.abs(v-o)
  jitter=np.frombuffer(b,dtype='<f4',count=4,offset=1072);oldjitter=np.frombuffer(prev[0],dtype='<f4',count=2,offset=1072)
  actualMotion=load(i,info['motionResource'],'<f2',2)[ys[same],xs[same]].astype(np.float64)
  projected=(pc[:2,same]/pc[3,same]*.5+.5)*np.array([[w],[h]])
  expectedMotion=(projected-np.array([xs[same]+.5,ys[same]+.5])).T+jitter[:2]-jitter[2:]
  validity=load(i,'motion.valid','<u4',1)[ys[same],xs[same],0]==1
  error=np.linalg.norm(actualMotion[validity]-expectedMotion[validity],axis=1)
  currentDisplay=display(i);ow=info['outputWidth'];oh=info['outputHeight']
  currentColors=sample(currentDisplay,(xs[same]+.5-jitter[0])*ow/w-.5,(ys[same]+.5-jitter[1])*oh/h-.5)
  previousColors=sample(previousDisplay,(projected[0]-oldjitter[0])*ow/w-.5,(projected[1]-oldjitter[1])*oh/h-.5)
  displayDifference=np.abs(currentColors-previousColors);previousDisplay=currentDisplay
  pairs.append({'displayRgbMae':float(displayDifference.mean()),'displayRgbP95':float(np.quantile(displayDifference,.95)),'motionErrorPixelsMax':float(error.max()) if len(error) else None,'motionErrorPixelsMean':float(error.mean()) if len(error) else None,'frame':i,'matchedLeaves':int(same.sum()),'hdrRgbMae':float(difference.mean()),'hdrRgbP95':float(np.quantile(difference,.95)),'hdrMean':float(v.mean()),'relativeMae':float(difference.mean()/max(v.mean(),1e-8))});prev=cur
 leaf=load(0,'gbuffer.material','<u4',2)[:,:,1]==5;valid=load(0,'motion.valid','<u4',1)[:,:,0];count=load(0,'indirect.accum','<f2',4)[:,:,3] if (d/'000-indirect.accum.bin.gz').exists() else np.full((h,w),np.nan,dtype=np.float32)
 row={'name':name,'reconstruction':info,'sourceGeometrySha256':report['sourceGeometrySha256'],'leafPixels':int(leaf.sum()),'leafValidFraction':float(np.mean(valid[leaf]==1)),'leafHistoryCountMean':float(np.mean(count[leaf])) if np.isfinite(count[leaf]).all() else None,'pairs':pairs,'meanRelativeMae':float(np.mean([x['relativeMae'] for x in pairs])),'meanDisplayMae':float(np.mean([x['displayRgbMae'] for x in pairs]))};rows.append(row)
 sheet=Image.new('RGB',(1280,4*384),(20,20,20));draw=ImageDraw.Draw(sheet);images=[]
 for i in range(8):
  im=Image.open(d/f'{i:03}-display.png').convert('RGB');images.append(im.resize((1280,720)));thumb=im.resize((640,360));x=(i%2)*640;y=(i//2)*384;sheet.paste(thumb,(x,y+24));draw.text((x+8,y+5),f'{name} / frame {i}',fill='white')
 sheet.save(out/f'{name}-8.png');images[0].save(out/f'{name}.gif',save_all=True,append_images=images[1:],duration=100,loop=0)
 print(name,'MV',row['leafValidFraction'],'history',row['leafHistoryCountMean'],'relative temporal MAE',row['meanRelativeMae'],'display MAE',row['meanDisplayMae'])
(out/'foliage-sequences.json').write_text(json.dumps({'sequences':rows,'scope':'Adjacent frozen-world camera rotation plus translation. RGB differences after first-visible static-world reprojection; material/normal and linear distance rejection. Nearest pixel resampling retains texture aliasing and stochastic shadows, so this is not a pure GI variance or perceptual ghosting metric. Display colors are bilinearly sampled in unjittered output coordinates. Missing GI history means the custom denoiser is culled; no fictitious count is assigned. Readbacks are untimed.'},indent=2))
