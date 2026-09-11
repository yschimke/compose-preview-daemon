#!/usr/bin/env python3
"""Run from repository root after warming bulk-font-cache; see BOOT-FONT-CACHE-BENCHMARK-CORRECTION.md."""
import json,subprocess,statistics
from pathlib import Path
variants=json.loads(Path('scripts/experiments/bulk-argb-cached.json').read_text())
rows=[];reference=None
for variant in variants:
 name=variant['name'];out=Path('daemon/android/build/bulk-argb-cached-reloads-'+name)
 cmd=['python3','scripts/benchmark-worker-startup.py','--classpath',variant['classpath'],'--java','/usr/lib/jvm/java-17-openjdk/bin/java','--renders','300','--fixture','ReloadDashboardPreview','--class-name','benchmark.screens.ReloadDashboardPreviewsKt','--user-class-dir','daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar','--swap-every','1','--reuse-output','--width','480','--height','1200','--density','2','--memory','--output',str(out)]
 print('Starting',name,flush=True)
 with Path('/tmp/bulk-argb-cached-reloads-'+name+'.log').open('w') as log:
  subprocess.run(cmd+['--jvm-arg='+f for f in variant['jvmArgs']],stdout=log,stderr=subprocess.STDOUT,check=True)
 s=json.loads((out/'summary.json').read_text());assert s['swapEvery']==1 and len(s['renders'])==301;assert len({r['classLoaderHashCode'] for r in s['renders'][1:]})==300
 fingerprints=[(r['pngSha256'],r['uiaSha256']) for r in s['renders']]
 if reference is None:reference=fingerprints
 assert fingerprints==reference
 rows.append({'variant':name,'source':str(out),'totalCpuMs':s['totalCpuMs'],'totalWallMs':s['totalWallMs'],'endPssKiB':s['workloadEndMemoryKiB']['Pss'],'parityFrames':len(fingerprints),'distinctApplicationLoaders':300,'windows':[{'from':i,'through':i+49,'meanCpuMs':statistics.mean(r['cpuMs'] for r in s['renders'][i:i+50]),'medianWallMs':statistics.median(r['wallMs'] for r in s['renders'][i:i+50])} for i in range(1,301,50)]})
 Path('/tmp/bulk-argb-cached-reloads-progress.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows[-1]),flush=True)
