"""Six frozen-world lighting captures; no FPS or aesthetic acceptance target."""
import argparse,json
from pathlib import Path
from frozen_lighting_comparison import analyze

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--option',required=True);p.add_argument('--label',required=True)
    p.add_argument('--low',type=int,required=True);p.add_argument('--high',type=int,required=True);a=p.parse_args()
    try:analyze(a.run,a.output,a.high,a.option,a.label,a.low)
    except Exception as error:
        if a.output.is_dir() and not (a.output/'analysis.json').exists():(a.output/'failure.json').write_text(json.dumps({'executionChecksPassed':False,'failure':repr(error)},indent=2)+'\n')
        raise
