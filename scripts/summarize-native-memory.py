#!/usr/bin/env python3
"""Correlate checkpoint NMT reservations with Linux smaps without apportioning mixed VMAs.

NMT committed bytes and smaps PSS measure different things. Unmatched anonymous
VMAs include JVM malloc arenas as well as third-party allocations and free pages;
they must not be labelled a native leak or entirely third-party memory.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

RESERVATION = re.compile(r'^\[(0x[0-9a-f]+) - (0x[0-9a-f]+)\] reserved(?: and committed)? \d+KB for (.+?) from$', re.M)
MAPPING = re.compile(r'^([0-9a-f]+)-([0-9a-f]+)\s+\S+\s+\S+\s+\S+\s+\d+(?:\s+(.*))?$')
METRIC = re.compile(r'^(Size|Rss|Pss|Private_Clean|Private_Dirty|Anonymous):\s+(\d+) kB$')


def correlate(native, smaps):
    regions = [(int(a, 16), int(b, 16), name) for a, b, name in RESERVATION.findall(native)]
    if not regions:
        raise ValueError('NMT detail has no recognized reservations')
    mappings = []
    for line in smaps.splitlines():
        match = MAPPING.match(line)
        if match:
            mappings.append({'start': int(match[1], 16), 'end': int(match[2], 16), 'path': match[3] or ''})
        elif (match := METRIC.match(line)) and mappings:
            mappings[-1][match[1]] = int(match[2])
    if not mappings:
        raise ValueError('No smaps mappings')
    groups = defaultdict(lambda: defaultdict(int))
    mixed = []
    for mapping in mappings:
        if not all(key in mapping for key in ['Size', 'Rss', 'Pss', 'Private_Clean', 'Private_Dirty', 'Anonymous']):
            raise ValueError('Incomplete smaps mapping')
        overlaps = [(a, b, name) for a, b, name in regions if a < mapping['end'] and b > mapping['start']]
        if len(overlaps) == 1 and overlaps[0][0] <= mapping['start'] and mapping['end'] <= overlaps[0][1]:
            group = 'NMT reserved: ' + overlaps[0][2]
        elif overlaps:
            group = 'mixed/partial NMT reservation'
            mixed.append({'sizeKiB': mapping['Size'], 'pssKiB': mapping['Pss'],
                          'categories': sorted({r[2] for r in overlaps})})
        elif mapping['path'].startswith('/'):
            group = 'file: ' + Path(mapping['path']).name
        else:
            group = 'outside NMT reservations: ' + (mapping['path'] or 'anonymous')
        for key in ['Size', 'Rss', 'Pss', 'Private_Clean', 'Private_Dirty', 'Anonymous']:
            groups[group][key] += mapping.get(key, 0)
    categories = {name: {'reservedKiB': int(reserved), 'committedKiB': int(committed)}
                  for name, reserved, committed in re.findall(r'^-\s+(.+?) \(reserved=(\d+)KB, committed=(\d+)KB\)', native, re.M)}
    totals = re.search(r'Total: reserved=(\d+)KB, committed=(\d+)KB', native)
    return {'nmtCategories': categories,
            'nmtTotalCommittedKiB': int(totals[2]) if totals else None,
            'smapsTotalPssKiB': sum(m['Pss'] for m in mappings),
            'mappingGroupsKiB': dict(sorted(groups.items(), key=lambda kv: -kv[1]['Pss'])),
            'mixedMappings': mixed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', type=Path, nargs='+')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    runs = []
    for directory in args.directories:
        checkpoints = json.loads((directory / 'heap-checkpoints.json').read_text())
        result = []
        for point in checkpoints:
            row = correlate((directory / point['nativeMemoryFile']).read_text(),
                            (directory / point['smapsFile']).read_text())
            row.update(renderIndex=point['renderIndex'], jvmFlags=point.get('jvmFlags'),
                       snapshotsElapsedMs=point['nativeMemoryElapsedMs'])
            result.append(row)
        runs.append({'source': str(directory), 'checkpoints': result})
    result = {'runs': runs, 'note': 'Sequential diagnostic snapshots, not atomic measurements. '
              'PSS is attributed only when a whole VMA belongs to one NMT reservation; mixed VMAs remain unassigned. '
              'Unmatched anonymous memory includes JVM malloc and allocator-retained pages, not just third-party allocations. '
              'NMT commitment is not residency; summing smaps PSS can differ slightly from smaps_rollup due to rounding.'}
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
