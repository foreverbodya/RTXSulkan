"""Controlled camera-bob counterexample using an actual captured base projection.

CPU experiment only. Float32 emulation of the new shader formula is compared
with analytic inverse rigid transforms, not GPU execution or visual acceptance.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np


def unit(value):
    return value/np.linalg.norm(value)


def rotation(axis, angle):
    c,s=math.cos(angle),math.sin(angle)
    if axis=='x': return np.array([[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1.]])
    return np.array([[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1.]])


def check(frame_path):
    raw=frame_path.read_bytes()
    frame=np.frombuffer(raw,dtype='<f4')
    projection=frame[:16].reshape(4,4,order='F').astype(float)
    view=frame[16:32].reshape(4,4,order='F').astype(float)
    if np.max(np.abs(projection[:2,3]))>1e-7 or abs(projection[3,3])>1e-7:
        raise ValueError('Expected a base projection with no bob translation')
    ip0=np.linalg.inv(projection); iv=np.linalg.inv(view)
    maximum_direction=maximum_origin=maximum_legacy_angle=0.
    cases=0;worst=None
    for bob in [0.,.02,.05,.1]:
        for walk in np.linspace(0.,2.,33):
            phase=walk*math.pi
            translation=np.eye(4);translation[:3,3]=[math.sin(phase)*bob*.5,-abs(math.cos(phase)*bob),0]
            transform=translation@rotation('z',math.radians(math.sin(phase)*bob*3.))@rotation('x',math.radians(abs(math.cos(phase-.2)*bob)*5.))
            inverse_transform=np.linalg.inv(transform)
            actual_projection=(projection@transform).astype(np.float32)
            ip=np.linalg.inv(actual_projection.astype(float)).astype(np.float32)
            iv32=iv.astype(np.float32)
            centre=iv32@ip[:,2]
            expected_origin=(iv@inverse_transform@np.array([0,0,0,1.]))[:3]
            origin_error=float(np.max(np.abs(centre[:3]/centre[3]-expected_origin)))
            maximum_origin=max(maximum_origin,origin_error)
            for u in np.linspace(.05,.95,9):
                for v in np.linspace(.05,.95,9):
                    base=ip0@np.array([u*2-1,v*2-1,1.,1.])
                    expected=unit(iv[:3,:3]@inverse_transform[:3,:3]@base[:3])
                    far=iv32@(ip@np.array([u*2-1,v*2-1,0.,1.],dtype=np.float32))
                    corrected=unit(far[:3]*centre[3]-centre[:3]*far[3])
                    near=ip@np.array([u*2-1,v*2-1,1.,1.],dtype=np.float32)
                    legacy=unit(iv32[:3,:3]@(near[:3]/near[3]))
                    error=float(np.max(np.abs(corrected-expected)))
                    angle=math.degrees(math.acos(float(np.clip(np.dot(legacy,expected),-1.,1.))))
                    maximum_direction=max(maximum_direction,error)
                    if angle>maximum_legacy_angle:
                        maximum_legacy_angle=angle
                        worst={'bob':bob,'walk':float(walk),'uv':[float(u),float(v)],'legacyAngleDegrees':angle,
                               'expectedDirection':expected.tolist(),'legacyDirection':legacy.tolist(),'correctedDirection':corrected.tolist()}
                    if error>2e-6 or origin_error>2e-6: raise AssertionError((error,origin_error))
                    cases+=1
    if maximum_legacy_angle<5.:raise AssertionError('Controlled translation did not expose the original bug')
    return {'passed':True,'cases':cases,'frameSha256':hashlib.sha256(raw).hexdigest(),
            'maxDirectionComponentError':maximum_direction,'maxOriginComponentErrorMetres':maximum_origin,
            'maxLegacyAngleDegrees':maximum_legacy_angle,'worstLegacyCase':worst,
            'scope':'CPU controlled perspective/bob counterexample on actual base matrices; analytic inverse transforms vs Float32 emulation. Not actual shader execution, Minecraft walk capture or visual acceptance.'}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('frame',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    result=check(args.frame)
    result['shaderSha256']=hashlib.sha256((Path(__file__).resolve().parents[2]/'src/client/resources/assets/sulkan/shaders/include/camera_rays.glsl').read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key not in ['scope','worstLegacyCase']}))
