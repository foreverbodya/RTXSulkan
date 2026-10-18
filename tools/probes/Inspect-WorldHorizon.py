"""Forensic read of actual sky/depth/HDR; no rendered-quality acceptance."""
import argparse,hashlib,json,math
from pathlib import Path
import numpy as np

def inspect(root,capture,out):
    if out.exists():raise FileExistsError('New report required')
    stem='lighting-audit-'+capture
    meta=json.loads((root/(stem+'-inputs.json')).read_text());w,h=meta['width'],meta['height']
    paths=[root/(stem+'-'+name+'.bin') for name in ['ray.direct.hdr','reconstruction.depth','atmosphere.sky','lighting.audit.sun']]
    frame=(root/(stem+'-frame.bin')).read_bytes();f=np.frombuffer(frame,dtype='<f4');ip=f[32:48].astype(float).reshape(4,4,order='F');iv=f[48:64].astype(float).reshape(4,4,order='F')
    hdr=np.fromfile(paths[0],dtype='<f2').reshape(h,w,4).astype(float)
    depth=np.fromfile(paths[1],dtype='<f4').reshape(h,w);sky=np.fromfile(paths[2],dtype='<f2').reshape(128,256,4).astype(float)
    label=np.fromfile(paths[3],dtype='<f4').reshape(h,w,4)[:,:,3]
    if not all(np.isfinite(v).all() for v in [hdr,depth,sky]):raise ValueError('Nonfinite capture')
    y,x=np.mgrid[:h,:w];clip=np.stack([(x+.5)/w*2-1,(y+.5)/h*2-1,np.where(depth>1e-7,depth,1),np.ones((h,w))],axis=-1)
    eye=clip@ip.T;relative=(eye[:,:,:3]/eye[:,:,3:4])@iv[:3,:3].T
    direction=relative/np.linalg.norm(relative,axis=2,keepdims=True)
    missing=depth<=1e-7;below=missing & (direction[:,:,1]<0)
    observer=np.clip((float(f[121])+float(f[125])-64)*.001,.001,50)
    ground_discriminant=((6360+observer)*direction[:,:,1])**2-observer*(12720+observer)
    planet=below & (ground_discriminant>=0)
    angular_pixel=2/(max(abs(float(f[5])),.01)*h)
    sun=f[112:115].astype(float);sun/=np.linalg.norm(sun)
    safe=below & ((direction@sun)<math.cos(.004675+angular_pixel*.5))
    ids=np.flatnonzero(safe.ravel())[::7];d=direction.reshape(-1,3)[ids]
    azimuth=np.arctan2(d[:,2],d[:,0]);uv=np.stack([azimuth/(2*math.pi)+.5,.5+.5*np.sign(d[:,1])*np.sqrt(np.abs(d[:,1]))],axis=1)
    location=uv*np.array([256,128])-.5;base=np.floor(location).astype(np.int64);frac=location-base
    x0=base[:,0]%256;x1=(x0+1)%256;y0=np.clip(base[:,1],0,127);y1=np.clip(base[:,1]+1,0,127)
    a=sky[y0,x0,:3]*(1-frac[:,0:1])+sky[y0,x1,:3]*frac[:,0:1]
    b=sky[y1,x0,:3]*(1-frac[:,0:1])+sky[y1,x1,:3]*frac[:,0:1]
    expected=a*(1-frac[:,1:2])+b*frac[:,1:2];actual=hdr.reshape(-1,4)[ids,:3]
    delta=np.abs(expected-actual);limits=.001+.005*np.abs(expected)
    cylindrical=np.maximum(np.linalg.norm(relative[:,:,[0,2]],axis=2),np.abs(relative[:,:,1]));terrain=(label>0) & (depth>1e-7)
    start,end=map(float,f[133:135]);fogged=terrain & (cylindrical>=end);transition=terrain & (cylindrical>start) & (cylindrical<end)
    report={'capture':capture,'width':w,'height':h,'belowHorizonMissingWorldPixels':int(below.sum()),
            'missingWorldPixelsLookingAtPlanetGround':int(planet.sum()),'skySourceSamples':len(ids),
            'maxActualSkyVersusCapturedLutError':float(delta.max()),'inheritedVisibleSkyBudgetExceededSamples':int((delta>limits).any(axis=1).sum()),
            'renderDistanceFogStart':start,'renderDistanceFogEnd':end,'terrainPixelsBeyondRenderFogEnd':int(fogged.sum()),
            'terrainPixelsInRenderFogTransition':int(transition.sum()),'sourceFiles':[{'path':p.name,'sha256':hashlib.file_digest(p.open('rb'),'sha256').hexdigest()} for p in paths],
            'scope':'Actual missing-world below-horizon HDR versus its captured sky LUT, using the inherited .001+.005*abs(expected) visible-sky budget. Planet-ground intersection and terrain/fog-range counts identify integration inputs, not a physical sky or finished-image reference.'}
    out.write_text(json.dumps(report,indent=2)+'\n')
    if len(ids)<100 or report['inheritedVisibleSkyBudgetExceededSamples']:raise ValueError('Visible lower-sky source comparison failed; see preserved report')
    print(json.dumps({k:v for k,v in report.items() if k not in ['sourceFiles','scope']}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('capture_directory',type=Path);p.add_argument('capture');p.add_argument('output',type=Path);a=p.parse_args();inspect(a.capture_directory,a.capture,a.output)
