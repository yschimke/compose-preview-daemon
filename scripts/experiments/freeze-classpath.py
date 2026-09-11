#!/usr/bin/env python3
"""Preserve mutable workspace jars before another build, retaining classpath order.

Gradle cache entries remain in place; every entry's bytes are fingerprinted in the
manifest. This does not support directory classpath entries. Use a fresh output
directory for each baseline or candidate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    inputs = [Path(line).resolve() for line in args.classpath.read_text().splitlines()]
    if not inputs or any(not path.is_file() for path in inputs):
        parser.error('classpath must contain only existing files')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    entries = []
    for index, source in enumerate(inputs):
        target = source
        if source.is_relative_to(workspace):
            target = output / f'{index}-{source.name}'
            shutil.copy2(source, target)
        entries.append({'source': str(source), 'path': str(target),
            'copied': target != source,
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    (output / 'classpath.txt').write_text(''.join(entry['path'] + '\n' for entry in entries))
    (output / 'inputs.json').write_text(json.dumps(entries, indent=2) + '\n')
    print(json.dumps({'classpath': str(output / 'classpath.txt'), 'entries': len(entries),
        'copied': sum(entry['copied'] for entry in entries)}))


if __name__ == '__main__':
    main()
