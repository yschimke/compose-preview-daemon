#!/usr/bin/env python3
"""Compare worker cohorts sharing a Linux CPU set; report wall time, CPU and resident memory.

RSS high-water sums are NOT simultaneous physical-memory peaks. Per-worker PSS snapshots
apportion shared pages at their observation time. Outputs retain individual worker logs/samples.
"""
import argparse
import json
import math
import os
from pathlib import Path
import signal
import statistics
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', type=Path, required=True)
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', required=True, help='Comma-separated logical CPU IDs shared by all workers')
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--trials', type=int, default=3)
    parser.add_argument('--renders', type=int, default=48)
    parser.add_argument('--fixture', action='append', default=[])
    args = parser.parse_args()
    cpus = [int(cpu) for cpu in args.cpus.split(',')]
    if not cpus or len(set(cpus)) != len(cpus) or not set(cpus) <= os.sched_getaffinity(0):
        parser.error('CPUs must be distinct and available to this process')
    if not 1 <= args.workers <= 16 or args.trials < 1 or args.renders < 1:
        parser.error('workers must be 1..16; trials/renders must be positive')
    variants = json.loads(args.matrix.read_text())
    names = [v['name'] for v in variants]
    if not names or len(set(names)) != len(names) or any(not n.replace('-', '').replace('_', '').isalnum() for n in names):
        parser.error('variant names must be unique alphanumeric/dash/underscore identifiers')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'cpus': cpus, 'workersPerCohort': args.workers, 'trials': args.trials,
        'rendersAfterRed': args.renders, 'variants': variants,
        'memoryNote': 'KiB. Summed worker HWM is not simultaneous physical-memory peak; PSS is per-worker snapshot.',
        'cohorts': []}
    reference = None
    for trial in range(args.trials):
        offset = trial % len(variants)
        for variant in variants[offset:] + variants[:offset]:
            cohort = output / f"{trial}-{variant['name']}"
            cohort.mkdir()
            jobs = []
            logs = []
            started = time.monotonic()
            try:
                for worker in range(args.workers):
                    cmd = ['taskset', '--cpu-list', args.cpus, sys.executable,
                        str(Path(__file__).with_name('benchmark-worker-startup.py')),
                        '--classpath', variant.get('classpath', str(args.classpath.resolve())),
                        '--java', variant.get('java', args.java), '--output', str(cohort / str(worker)),
                        '--renders', str(args.renders), '--memory']
                    for flag in variant.get('jvmArgs', []):
                        cmd.append('--jvm-arg=' + flag)
                    for fixture in args.fixture:
                        cmd += ['--fixture', fixture]
                    log = (cohort / f'{worker}.log').open('w')
                    logs.append(log)
                    jobs.append(subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True))
                for index, job in enumerate(jobs):
                    if job.wait(timeout=600):
                        raise RuntimeError(f'Worker {index} failed; see {cohort}')
                elapsed = (time.monotonic() - started) * 1000
            finally:
                for job in jobs:
                    if job.poll() is None:
                        try:
                            os.killpg(job.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                for job in jobs:
                    try:
                        job.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(job.pid, signal.SIGKILL)
                        job.wait(timeout=10)
                for log in logs:
                    log.close()
            summaries = [json.loads((cohort / str(i) / 'summary.json').read_text()) for i in range(args.workers)]
            for summary in summaries:
                hashes = [(r['fixture'], r['pngSha256'], r['uiaSha256']) for r in summary['renders']]
                if reference is None:
                    reference = hashes
                if hashes != reference or len(hashes) != args.renders + 1:
                    raise RuntimeError(f'Artifact parity mismatch: {cohort}')
                if not summary['memorySamplesKiB'] or summary['workloadEndMemoryKiB']['Pss'] <= 0:
                    raise RuntimeError(f'Missing memory measurements: {cohort}')
            frames = args.workers * (args.renders + 1)
            latency = sorted(r['wallMs'] for s in summaries for r in s['renders'])
            row = {'trial': trial, 'variant': variant['name'], 'frames': frames, 'parity': True,
                'cohortWallMs': round(elapsed), 'framesPerSecondIncludingStartupAndShutdown': frames * 1000 / elapsed,
                'totalWorkerCpuMs': sum(s['totalCpuMs'] for s in summaries),
                'cpuMsPerFrameIncludingStartup': sum(s['totalCpuMs'] for s in summaries) / frames,
                'readyMedianMs': statistics.median(s['readyWallMs'] for s in summaries),
                'readyMaxMs': max(s['readyWallMs'] for s in summaries),
                'renderMedianMs': statistics.median(latency), 'renderP95Ms': latency[math.ceil(.95 * len(latency)) - 1],
                'sumWorkerHighWaterKiB': sum(s['workloadEndMemoryKiB']['VmHWM'] for s in summaries),
                'endPssMedianKiB': statistics.median(s['workloadEndMemoryKiB']['Pss'] for s in summaries),
                'startSpreadMs': round(1000 * (max(s['startedUnixSeconds'] for s in summaries) - min(s['startedUnixSeconds'] for s in summaries))),
                'workers': [{k: s[k] for k in ('readyWallMs', 'readyCpuMs', 'totalWallMs', 'totalCpuMs',
                    'readyMemoryKiB', 'workloadEndMemoryKiB', 'hostLoadAverage')} for s in summaries]}
            report['cohorts'].append(row)
            (output / 'contention-summary.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({k: v for k, v in row.items() if k != 'workers'}), flush=True)


if __name__ == '__main__':
    main()
