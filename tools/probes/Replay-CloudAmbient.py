"""Check captured cloud columns with scalar angular quadrature.

The continuous angular integral is evaluated independently by adaptive Simpson
integration and reported separately from GPU implementation error. It does not
prove that plane-parallel columns describe full 3D cloud visibility.
"""
import argparse
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import struct

MU=(.0198550718,.1016667613,.2372337950,.4082826788,.5917173212,.7627662050,.8983332387,.9801449282)
WEIGHT=(.0506142681,.1111905172,.1568533229,.1813418917,.1813418917,.1568533229,.1111905172,.0506142681)


@lru_cache(maxsize=None)
def continuous(tau):
    if tau == 0:
        return 1.0
    def f(mu):
        return math.exp(-tau/mu) if mu > 0 else 0.0
    def refine(a,b,fa,fm,fb,whole,tolerance,depth):
        middle=(a+b)*.5
        fl,fr=f((a+middle)*.5),f((middle+b)*.5)
        left=(middle-a)/6*(fa+4*fl+fm)
        right=(b-middle)/6*(fm+4*fr+fb)
        delta=left+right-whole
        if depth==0:
            raise ArithmeticError("Continuous hemisphere oracle did not converge")
        if abs(delta) <= 15*tolerance:
            return left+right+delta/15
        return refine(a,middle,fa,fl,fm,left,tolerance/2,depth-1)+refine(middle,b,fm,fr,fb,right,tolerance/2,depth-1)
    fa,fm,fb=f(0),f(.5),f(1)
    return refine(0,1,fa,fm,fb,(fa+4*fm+fb)/6,1e-10,40)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory",type=Path)
    parser.add_argument("output",type=Path)
    parser.add_argument("--phase",required=True)
    args=parser.parse_args()
    raw=(args.directory/f"cloud-ambient-{args.phase}-columns.bin").read_bytes()
    positions=(args.directory/f"cloud-ambient-{args.phase}-positions.bin").read_bytes()
    if len(raw)!=128*64*16 or len(positions)!=len(raw):
        raise ValueError("Unexpected ambient buffer extent")
    checked,occluded=0,0
    maximum,approximation=0.0,0.0
    failures=[]
    for i in range(128*64):
        p=struct.unpack_from("<4f",positions,i*16)
        values=struct.unpack_from("<4f",raw,i*16)
        if not all(map(math.isfinite,(*p,*values))):
            raise ValueError(f"Nonfinite ambient probe {i}")
        for side in range(2):
            tau,actual=values[side],values[side+2]
            if not 0<=tau<=5.761 or not 0<=actual<=1:
                failures.append({"pixel":i,"side":side,"tau":tau,"actual":actual,"reason":"range"})
                continue
            expected=sum(weight*math.exp(-tau/mu) for mu,weight in zip(MU,WEIGHT))
            error=abs(expected-actual)
            maximum=max(maximum,error)
            approximation=max(approximation,abs(expected-continuous(tau)))
            if error>2e-6:
                failures.append({"pixel":i,"side":side,"expected":expected,"actual":actual,"error":error})
            checked+=1
            if actual<.8:
                occluded+=1
    report={"passed":not failures,"phase":args.phase,"hemisphereSamples":checked,
            "occludedSamples":occluded,"maxGpuQuadratureError":maximum,
            "maxAngularApproximationErrorAgainstAdaptiveIntegral":approximation,
            "continuousOracleAbsTolerance":1e-10,"failures":failures,
            "columnsSha256":hashlib.sha256(raw).hexdigest(),"positionsSha256":hashlib.sha256(positions).hexdigest(),
            "scope":"Actual captured optical depths and 8-point GPU angular quadrature. Continuous integral error is reported, not hidden in GPU tolerance. Does not replay source-PNG vertical density or solve lateral 3D cloud transport."}
    if args.output.exists():
        raise FileExistsError("Replay output must be new")
    args.output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="failures"}))
    return 0 if report['passed'] else 1


if __name__=="__main__":
    raise SystemExit(main())
