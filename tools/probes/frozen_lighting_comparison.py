"""Shared analysis of frozen-world lighting captures; no aesthetic PASS score."""
import hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def luminance(rgb):return sum(a*b for a,b in zip(rgb,[.2126,.7152,.0722]))
def analyze(run,out,mode,option='FOLIAGE_OPTICS',label='optics',low=0):
    terminal=load(run/'result.json')
    if not terminal['passed'] or not terminal['runtimeModsUnchanged']:raise ValueError('Run did not pass its declared gates')
    source=run/'evidence' if (run/'evidence').is_dir() else run
    out.mkdir(parents=True,exist_ok=False)
    phases={};geometries=set();poses=[];views=set();projection_offsets=[];option_reference=None;files=set()
    for phase in ['day','sunset']:
        rows=[]
        for trial,optics in enumerate([low,mode,low],1):
            capture=f'{phase}-{label}-{optics}-trial-{trial}'
            cadence=load(source/f'{capture}-cadence.json');audit=load(source/f'lighting-audit-{capture}.json')
            exposure=load(source/f'{capture}-exposure.json');inputs=load(source/f'lighting-audit-{capture}-inputs.json')
            if not all(cadence[key] for key in ['serverFrozen','poseCheckedEveryFrame','steadyGeometry']) or cadence['measuredSeconds']<20 or cadence['frames']<100:raise ValueError('Frozen camera/geometry or capture interval differs')
            if cadence['comparisonOption']!=option or cadence['comparisonValue']!=optics or inputs['activeOptions'][option]!=optics:raise ValueError('Wrong actual option')
            geometries.add(cadence['geometrySha256']);geometries.add(audit['geometrySha256'])
            pose=cadence['actualPoseEnd'];poses.append({key:pose[key] for key in ['eye','forward','directionBasis']})
            projection_offsets.append({'capture':capture,'radians':pose['projectionDirectionOffsetRadians']})
            frame=(source/f'lighting-audit-{capture}-frame.bin').read_bytes()
            if len(frame)!=1152:raise ValueError('Frame contract differs')
            views.add(frame[192:256])
            options={k:v for k,v in inputs['activeOptions'].items() if k!=option}
            if option_reference is None:option_reference=options
            if options!=option_reference:raise ValueError('Unrelated pack option changed')
            groups={}
            for name,group in audit['groups'].items():
                groups[name]={'samples':group['samples'],'meanUnexposedLocalLuminance':sum(luminance(group['mean'+term]) for term in ['PrimarySun','ReflectedDiffuse','TransmittedDiffuse','LocalEmission']),
                              'meanLinearAlbedo':group['meanLinearAlbedo'],'fractionBelow0001':group['fractionLocalLuminanceBelow0001']}
            rows.append({'capture':capture,'optics':optics,'groups':groups,'actualExposureEV':exposure['actualEV'],'coefficients':audit['opticsCoefficients']})
            for p in source.iterdir():
                if p.is_file() and (capture in p.name):files.add(p)
        phases[phase]={'trials':rows,'groupComparison':{}}
        for group in sorted(set.intersection(*(set(row['groups']) for row in rows))):
            a,b,c=[row['groups'][group]['meanUnexposedLocalLuminance'] for row in rows]
            phases[phase]['groupComparison'][group]={'candidateMinusOriginal':b-a,'returnMinusOriginal':c-a,'candidateOverOriginal':b/a if a else None,
                'scope':'Group means with different jitter/ray seeds and potentially different sun-category membership, not matched-pixel errors.'}
    if len(geometries)!=1 or len(views)!=1 or any(p!=poses[0] for p in poses):raise ValueError('Scene geometry, actual camera or inverse-view matrix changed')
    report={'executionChecksPassed':True,'hostSha256':terminal['artifactSha256'],'candidateMode':mode,'comparisonOption':option,'lowValue':low,'geometrySha256':next(iter(geometries)),
            'actualEyeAndViewForwardIdentical':True,'inverseViewMatrixByteIdentical':True,'projectionJitterOffsetsRadians':projection_offsets,
            'unrelatedOptionsIdentical':True,'phases':phases,'cinematicAcceptance':False,
            'scope':'Same loaded frozen world and camera, original/candidate/original in each phase. Raw component arithmetic and coefficient checks; stochastic samples/jitter remain unmatched. No converged transport, material calibration or performance claim.'}
    (out/'analysis.json').write_text(json.dumps(report,indent=2)+'\n')
    manifest=[]
    for p in sorted(files):manifest.append({'path':p.name,'bytes':p.stat().st_size,'sha256':hashlib.file_digest(p.open('rb'),'sha256').hexdigest()})
    (out/'capture-files.sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Actual full screenshot resized uniformly, no exposure/color retouching.
    sheet=Image.new('RGB',(1920,1140),(22,22,22));draw=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',20)
    for row,phase in enumerate(['day','sunset']):
        for col,(optics,trial,caption) in enumerate([(low,1,'Original'),(mode,2,'Candidate')]):
            name=f'natural-{phase}-{label}-{optics}-trial-{trial}.png'
            with Image.open(source/name) as image:sheet.paste(image.convert('RGB').resize((960,540),Image.Resampling.LANCZOS),(col*960,row*570+30))
            draw.text((col*960+12,row*570+5),f'{phase} | {option}={optics}',font=font,fill='white')
    sheet.save(out/'actual-display-comparison.png')
    print(json.dumps({'executionChecksPassed':True,'candidateMode':mode,'comparisonOption':option,'lowValue':low,'filesHashed':len(manifest),'geometrySha256':next(iter(geometries))}))
    return report

