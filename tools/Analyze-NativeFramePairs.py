"""Quantify actual display pairs and stationary temporal noise; never modifies source PNGs."""
import argparse
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image


def analyze(root):
    root=Path(root)
    evidence=root/"evidence"
    records=json.loads((evidence/"native-frame-comparison.json").read_text(encoding="utf-8"))
    rows=[]
    temporal={}
    for row in records:
        label=row.get("comparison","candidate")
        prefix=f'native-{"self-" if label=="self" else ""}{row["phase"]}-{row["sequence"]:02}'
        paths=[evidence/(prefix+f"-{name}.png") for name in ("reference","candidate")]
        a,b=[np.asarray(Image.open(p).convert("RGB")) for p in paths]
        if a.shape!=b.shape:raise ValueError("Display extent changed within a pair")
        difference=np.abs(a.astype(np.int16)-b.astype(np.int16))
        pixel=difference.max(axis=2)
        mse=float(np.mean(difference.astype(np.float64)**2))
        item={"phase":row["phase"],"sequence":row["sequence"],"comparison":label,
              "frame":row["frame"],"pixels":int(pixel.size),"meanRGBCodeDelta":float(difference.mean()),
              "maximumRGBCodeDelta":int(pixel.max()),"mseRGBCode":mse,
              "psnrDb":10*math.log10(255**2/mse) if mse else None,
              "pixelsAtOrAboveCodeDelta":{str(k):int(np.count_nonzero(pixel>=k)) for k in (1,2,4,8,16,32,64)}}
        point=np.unravel_index(np.argmax(pixel),pixel.shape)
        item["maximumAtXY"]=[int(point[1]),int(point[0])]
        rows.append(item)
        if row["phase"]=="static":
            group=temporal.setdefault(label,{"count":0,"sum":[np.zeros(a.shape,dtype=np.uint32) for _ in range(2)],
                                              "squared":[np.zeros(a.shape,dtype=np.uint32) for _ in range(2)]})
            group["count"]+=1
            for i,x in enumerate((a,b)):
                integer=x.astype(np.uint32)
                group["sum"][i]+=integer;group["squared"][i]+=integer*integer
    stats={}
    for label,group in temporal.items():
        n=group["count"]
        # Population temporal RMS over the same stationary sequence. A short QA
        # sequence (including any recorded history resets) is not a flicker guarantee.
        values=[]
        for total,squared in zip(group["sum"],group["squared"]):
            variance=np.maximum(squared.astype(np.float64)/n-(total.astype(np.float64)/n)**2,0)
            values.append(float(np.sqrt(variance.mean())))
        stats[label]={"frames":n,"referenceTemporalRmsCode":values[0],"candidateTemporalRmsCode":values[1],
                      "temporalRmsChangeCode":values[1]-values[0]}
    return {"root":str(root.resolve()),"pairs":rows,"stationaryTemporal":stats,
            "scope":"RGB8 actual native-DLSS display output. PSNR/temporal RMS are numerical comparisons, not proof of perceptual equivalence in every scene."}


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("runs",nargs="+");parser.add_argument("--output",required=True)
    args=parser.parse_args()
    result=[analyze(r) for r in args.runs]
    Path(args.output).write_text(json.dumps(result,indent=2),encoding="utf-8")
    for run in result:
        print(Path(run["root"]).name)
        for label in ("self","candidate"):
            a=[x for x in run["pairs"] if x["comparison"]==label]
            if not a:continue
            print(label,"pairs",len(a),"max",max(x["maximumRGBCodeDelta"] for x in a),
                  "pixels>=16",sum(x["pixelsAtOrAboveCodeDelta"]["16"] for x in a),
                  "worst PSNR",min((x["psnrDb"] for x in a if x["psnrDb"] is not None),default=None),
                  "temporal",run["stationaryTemporal"].get(label))
