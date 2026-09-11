#!/usr/bin/env python3
"""Summarize HotSpot -Xlog:gc*=info logs from completed worker trials.

GC CPU fields are rounded diagnostic samples, not an exclusive CPU profile.
Use totalCpuMs from the paired worker summaries for overall CPU comparisons.
"""
import argparse
import json
from pathlib import Path
import re
import statistics


def summarize(root):
    summary = json.loads((root / 'summary.json').read_text())
    pauses = []
    cpu = []
    for line in (root / 'worker.log').read_text().splitlines():
        match = re.search(r'GC\((\d+)\) (Pause .*) (\d+(?:\.\d+)?)ms$', line)
        if match:
            pauses.append((int(match[1]), match[2], float(match[3])))
        match = re.search(r'GC\((\d+)\) User=(\d+(?:\.\d+)?)s Sys=(\d+(?:\.\d+)?)s Real=(\d+(?:\.\d+)?)s$', line)
        if match:
            cpu.append((int(match[1]), 1000 * (float(match[2]) + float(match[3]))))
    if not pauses:
        raise ValueError(f'{root}: no completed GC pauses; enable -Xlog:gc*=info')
    explicit = [p for p in pauses if p[1].startswith('Pause Full (System.gc())')]
    ids = {p[0] for p in explicit}
    return {'source': str(root), 'jvmArgs': summary['jvmArgs'],
        'completedFrames': len(summary['renders']), 'totalWorkerCpuMs': summary['totalCpuMs'],
        'loggedPauseCount': len(pauses), 'loggedPauseTotalMs': sum(p[2] for p in pauses),
        'explicitFullGcCount': len(explicit), 'explicitFullGcPauseTotalMs': sum(p[2] for p in explicit),
        'last30ExplicitFullGcMedianMs': statistics.median(p[2] for p in explicit[-30:]) if explicit else None,
        'explicitFullGcReportedCpuMs': sum(t for gc_id, t in cpu if gc_id in ids),
        'note': 'Pauses cover worker lifetime, including startup. GC CPU fields are rounded diagnostic samples; use paired total worker CPU to measure net savings.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([summarize(root) for root in args.runs], indent=2))


if __name__ == '__main__':
    main()
