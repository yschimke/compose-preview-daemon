#!/usr/bin/env python3
"""Three rotated paired reload runs; allocator setting applies only to child workers."""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--classpath', type=Path, required=True)
parser.add_argument('--user-jar', type=Path, required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--renders', type=int, default=100)
args = parser.parse_args()
if args.renders < 1:
    parser.error('--renders must be positive')
# Avoid falsely identifying an overridden allocator as the glibc default.
if any(os.environ.get(k) for k in ['GLIBC_TUNABLES', 'LD_PRELOAD', 'MALLOC_ARENA_MAX']):
    parser.error('Run without inherited allocator overrides')
args.output.mkdir(parents=True, exist_ok=False)
rows = []
reference = None
for trial in range(3):
    names = ['default', 'arena2'] if trial % 2 == 0 else ['arena2', 'default']
    for name in names:
        output = args.output / f'{trial}-{name}'
        env = os.environ.copy()
        if name == 'arena2':
            env['MALLOC_ARENA_MAX'] = '2'
        command = [sys.executable, str(Path(__file__).resolve().parents[1] / 'benchmark-worker-startup.py'),
            '--classpath', str(args.classpath.resolve()), '--java', args.java,
            '--renders', str(args.renders), '--fixture', 'ReloadDashboardPreview',
            '--class-name', 'benchmark.screens.ReloadDashboardPreviewsKt',
            '--user-class-dir', str(args.user_jar.resolve()), '--swap-every', '1',
            '--reuse-output', '--width', '480', '--height', '1200', '--density', '2',
            '--memory', '--output', str(output.resolve())]
        flags = ['-Xmx256m', '-Xms32m', '-XX:+UseSerialGC', '-XX:MinHeapFreeRatio=10', '-XX:MaxHeapFreeRatio=30']
        command += ['--jvm-arg=' + f for f in flags]
        print('Starting', trial, name, flush=True)
        with (args.output / f'{trial}-{name}.log').open('w') as log:
            subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        summary = json.loads((output / 'summary.json').read_text())
        fingerprints = [(r['fixture'], r['pngSha256'], r['uiaSha256']) for r in summary['renders']]
        if reference is None:
            reference = fingerprints
        if fingerprints != reference:
            raise RuntimeError(f'Output mismatch: {trial} {name}')
        row = {k: summary[k] for k in ['readyWallMs', 'totalWallMs', 'totalCpuMs', 'workloadEndMemoryKiB', 'workloadEndFaults', 'allocatorEnvironment']}
        row.update(trial=trial, variant=name, parityFrames=len(fingerprints),
            distinctApplicationLoaders=len({r['classLoaderHashCode'] for r in summary['renders'][1:]}),
            last30MeanCpuMs=statistics.mean(r['cpuMs'] for r in summary['renders'][-30:]),
            last30MedianWallMs=statistics.median(r['wallMs'] for r in summary['renders'][-30:]))
        rows.append(row)
        (args.output / 'matrix-summary.json').write_text(json.dumps(rows, indent=2)+'\n')
        print(json.dumps(row), flush=True)
