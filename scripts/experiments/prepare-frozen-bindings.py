#!/usr/bin/env python3
"""Build isolated experimental Robolectric jars and verify the invalidation guard."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath', type=Path, required=True)
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
classpath = args.classpath.read_text().splitlines()
sandbox, = [p for p in classpath if '/org.robolectric/sandbox/4.17-beta-4/' in p]
asm, = [p for p in classpath if '/org.ow2.asm/asm/' in p]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
sources = Path(__file__).resolve().parent
subprocess.run([str(args.jdk / 'bin/javac'), '-cp', asm, '-d', str(output),
    str(sources / 'FreezeShadowBindings.java'), str(sources / 'VerifyFrozenInvalidator.java')], check=True)
variants = [{'name': 'baseline', 'classpath': str(args.classpath.resolve())}]
for name in ('frozen', 'constant'):
    jar = output / (name + '.jar')
    subprocess.run([str(args.jdk / 'bin/java'), '-cp', str(output) + ':' + asm,
        'FreezeShadowBindings', sandbox, str(jar), name], check=True)
    paths = [str(jar) if p == sandbox else p for p in classpath]
    cp = output / (name + '-classpath.txt')
    cp.write_text('\n'.join(paths) + '\n')
    subprocess.run([str(args.jdk / 'bin/java'), '-Xverify:all', '-cp',
        str(output) + ':' + ':'.join(paths), 'VerifyFrozenInvalidator'], check=True)
    variants.append({'name': name, 'classpath': str(cp)})
for variant in variants:
    variant['jvmArgs'] = ['-XX:TieredStopAtLevel=1',
        '-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30']
(output / 'matrix.json').write_text(json.dumps(variants, indent=2) + '\n')
(output / 'inputs.json').write_text(json.dumps({'sandbox': sandbox,
    'sandboxSha256': hashlib.sha256(Path(sandbox).read_bytes()).hexdigest(),
    'asm': asm}, indent=2) + '\n')
