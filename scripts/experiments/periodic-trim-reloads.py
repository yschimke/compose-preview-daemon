#!/usr/bin/env python3
"""Run one ordered trio of actual reloads using the sampled-metrics trim matrix."""
import argparse
import json,subprocess,statistics
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--java',required=True)
parser.add_argument('--user-jar',type=Path,required=True)
parser.add_argument('--matrix',type=Path,default=Path('scripts/experiments/sampled-metrics-trim.json'))
args=parser.parse_args()
variants=json.loads(args.matrix.read_text())
args.output.mkdir(parents=True,exist_ok=False)
rows=[];reference=None
for variant in variants:
 name=variant['name'];out=args.output/name
 cmd=['python3','scripts/benchmark-worker-startup.py','--classpath',variant['classpath'],'--java',args.java,'--renders','300','--fixture','ReloadDashboardPreview','--class-name','benchmark.screens.ReloadDashboardPreviewsKt','--user-class-dir',str(args.user_jar),'--swap-every','1','--reuse-output','--width','480','--height','1200','--density','2','--memory','--output',str(out)]
 print('Starting',name,flush=True)
 with (args.output/(name+'.log')).open('w') as log:
  subprocess.run(cmd+['--jvm-arg='+f for f in variant['jvmArgs']],stdout=log,stderr=subprocess.STDOUT,check=True)
 s=json.loads((out/'summary.json').read_text());assert s['swapEvery']==1 and len(s['renders'])==301;assert len({r['classLoaderHashCode'] for r in s['renders'][1:]})==300
 fingerprints=[(r['pngSha256'],r['uiaSha256']) for r in s['renders']]
 if reference is None:reference=fingerprints
 assert fingerprints==reference
 rows.append({'variant':name,'source':str(out),'totalCpuMs':s['totalCpuMs'],'totalWallMs':s['totalWallMs'],'endPssKiB':s['workloadEndMemoryKiB']['Pss'],'parityFrames':len(fingerprints),'distinctApplicationLoaders':300,'windows':[{'from':i,'through':i+49,'meanCpuMs':statistics.mean(r['cpuMs'] for r in s['renders'][i:i+50]),'medianWallMs':statistics.median(r['wallMs'] for r in s['renders'][i:i+50])} for i in range(1,301,50)]})
 (args.output/'matrix-summary.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows[-1]),flush=True)
