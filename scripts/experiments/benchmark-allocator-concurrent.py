#!/usr/bin/env python3
"""Compare allocator, compiler or measurement policies with two workers sharing CPU affinity."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--classpath', type=Path, required=True)
parser.add_argument('--user-jar', type=Path, required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--cpus', required=True, help='Comma-separated logical CPUs shared by both workers')
parser.add_argument('--renders', type=int, default=60)
parser.add_argument('--policy', choices=['allocator', 'compiler', 'metrics'], default='allocator')
parser.add_argument('--font-cache', type=Path,
    help='Existing warmed font cache; enables offline mode, required for metrics policy')
args = parser.parse_args()
if args.policy == 'metrics' and args.font_cache is None:
    parser.error('metrics policy requires a warmed --font-cache')
if args.font_cache is not None and not args.font_cache.is_dir():
    parser.error('--font-cache must be an existing directory')
if args.policy == 'metrics':
    for face in ['roboto-400.woff2', 'roboto-500.woff2']:
        path = args.font_cache / face
        if not path.is_file() or path.read_bytes()[:4] != b'wOF2':
            parser.error(f'Warm font cache is missing a valid {face}')
if args.renders < 1 or not re.fullmatch(r'\d+(,\d+)*', args.cpus):
    parser.error('Positive renders and comma-separated CPU numbers required')
if not set(map(int,args.cpus.split(','))) <= os.sched_getaffinity(0):
    parser.error('Requested CPUs are outside current affinity')
if any(os.environ.get(k) for k in ['GLIBC_TUNABLES','LD_PRELOAD','MALLOC_ARENA_MAX']):
    parser.error('Run without inherited allocator overrides')
args.output.mkdir(parents=True, exist_ok=False)
reference = None
rows = []
candidate = {'allocator':'arena2','compiler':'compiler2','metrics':'metrics5'}[args.policy]
for trial in range(3):
    for variant in (['default',candidate] if trial % 2 == 0 else [candidate,'default']):
        directory = args.output / f'{trial}-{variant}'
        directory.mkdir()
        env = os.environ.copy()
        if variant == 'arena2':
            env['MALLOC_ARENA_MAX'] = '2'
        print('Starting',trial,variant,flush=True)
        samples=[]
        affinities={}
        with ExitStack() as stack:
            processes=[]
            started=time.monotonic()
            for worker in range(2):
                output=directory/str(worker)
                command=['taskset','-c',args.cpus,sys.executable,
                    str(Path(__file__).resolve().parents[1]/'benchmark-worker-startup.py'),
                    '--classpath',str(args.classpath.resolve()),'--java',args.java,
                    '--renders',str(args.renders),'--fixture','ReloadDashboardPreview',
                    '--class-name','benchmark.screens.ReloadDashboardPreviewsKt',
                    '--user-class-dir',str(args.user_jar.resolve()),'--swap-every','1',
                    '--reuse-output','--width','480','--height','1200','--density','2',
                    '--memory','--output',str(output.resolve())]
                command += ['--jvm-arg='+f for f in ['-Xmx256m','-Xms32m','-XX:+UseSerialGC',
                    '-XX:MinHeapFreeRatio=10','-XX:MaxHeapFreeRatio=30']]
                if variant == 'compiler2' or args.policy == 'metrics':
                    command.append('--jvm-arg=-XX:CICompilerCount=2')
                if args.policy == 'metrics':
                    cadence = 5 if variant == 'metrics5' else 1
                    command.append(f'--jvm-arg=-Dcomposeai.daemon.metrics.everyRenders={cadence}')
                if args.font_cache is not None:
                    command += ['--jvm-arg=-Dcomposeai.fonts.cacheDir='+str(args.font_cache.resolve()),
                        '--jvm-arg=-Dcomposeai.fonts.offline=true']
                log=stack.enter_context((directory/f'{worker}.log').open('w'))
                processes.append(subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT))
            # Let each harness own bounded worker cleanup, including if its peer fails.
            while any(p.poll() is None for p in processes):
                values=[]
                for worker in range(2):
                    try:
                        pid=int((directory/str(worker)/'worker.pid').read_text())
                        affinity=sorted(os.sched_getaffinity(pid))
                        assert affinity==sorted(set(map(int,args.cpus.split(',')))), 'Worker affinity mismatch'
                        affinities[worker]=affinity
                        smaps=Path(f'/proc/{pid}/smaps_rollup').read_text()
                        values.append(int(re.search(r'^Pss:\s+(\d+) kB$',smaps,re.M)[1]))
                    except (FileNotFoundError,ProcessLookupError):
                        break
                if len(values)==2:
                    samples.append({'elapsedMs':round((time.monotonic()-started)*1000),'pssKiB':values})
                time.sleep(1)
            elapsed=round((time.monotonic()-started)*1000)
            if any(p.returncode for p in processes):
                raise RuntimeError(f'Worker group failed: {trial} {variant}')
        workers=[]
        for worker in range(2):
            s=json.loads((directory/str(worker)/'summary.json').read_text())
            fingerprints=[(r['fixture'],r['pngSha256'],r['uiaSha256']) for r in s['renders']]
            if reference is None:
                reference=fingerprints
            assert fingerprints==reference, 'PNG/UIA mismatch'
            assert s['swapEvery']==1 and len(s['renders'])==args.renders+1
            assert len({r['classLoaderHashCode'] for r in s['renders'][1:]})==args.renders
            workers.append({k:s[k] for k in ['readyWallMs','totalWallMs','totalCpuMs',
                'workloadEndMemoryKiB','workloadEndFaults','allocatorEnvironment','jvmArgs']})
        assert samples, 'No overlapping-worker memory observations'
        assert len(affinities)==2, 'Missing worker affinity evidence'
        row={'policy':args.policy,'trial':trial,'variant':variant,'cpus':args.cpus,'groupElapsedMs':elapsed,
            'workerAffinities':affinities,
            'totalCpuMs':sum(w['totalCpuMs'] for w in workers),
            'peakObservedConcurrentPssKiB':max(sum(x['pssKiB']) for x in samples),
            'minorFaults':sum(w['workloadEndFaults']['minor'] for w in workers),
            'majorFaults':sum(w['workloadEndFaults']['major'] for w in workers),
            'parityFramesPerWorker':len(reference),'distinctApplicationLoadersPerWorker':args.renders,
            'workers':workers,'concurrentPssSamples':samples}
        rows.append(row)
        (args.output/'matrix-summary.json').write_text(json.dumps(rows,indent=2)+'\n')
        print(json.dumps({k:v for k,v in row.items() if k not in ['workers','concurrentPssSamples']}),flush=True)
