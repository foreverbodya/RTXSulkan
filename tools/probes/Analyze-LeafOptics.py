"""Compatibility entry point for shared frozen-world lighting analysis."""
import argparse,json
from pathlib import Path
from frozen_lighting_comparison import analyze

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('output',type=Path);p.add_argument('mode',type=int,choices=[1,2]);a=p.parse_args()
    try:analyze(a.run,a.output,a.mode)
    except Exception as error:
        if a.output.is_dir() and not (a.output/'analysis.json').exists():(a.output/'failure.json').write_text(json.dumps({'executionChecksPassed':False,'failure':repr(error)},indent=2)+'\n')
        raise
