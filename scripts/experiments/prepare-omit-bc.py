#!/usr/bin/env python3
"""Build a cost-bound-only jar omitting BC; not a behavior-preserving optimization."""
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
robolectric, = [p for p in classpath if '/org.robolectric/robolectric/' in p]
asm, = [p for p in classpath if '/org.ow2.asm/asm/' in p]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
subprocess.run([str(args.jdk / 'bin/javac'), '-cp', asm, '-d', str(output),
    str(Path(__file__).with_name('OmitBouncyCastle.java'))], check=True)
jar = output / 'omit-bc.jar'
subprocess.run([str(args.jdk / 'bin/java'), '-cp', str(output) + ':' + asm,
    'OmitBouncyCastle', robolectric, str(jar)], check=True)
patched = output / 'classpath.txt'
patched.write_text('\n'.join(str(jar) if p == robolectric else p for p in classpath) + '\n')
flags = ['-XX:TieredStopAtLevel=1', '-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30']
(output / 'matrix.json').write_text(json.dumps([
    {'name': 'baseline', 'classpath': str(args.classpath.resolve()), 'jvmArgs': flags},
    {'name': 'omit-bc', 'classpath': str(patched), 'jvmArgs': flags},
], indent=2) + '\n')
(output / 'inputs.json').write_text(json.dumps({
    'robolectric': robolectric,
    'robolectricSha256': hashlib.sha256(Path(robolectric).read_bytes()).hexdigest(),
    'asm': asm,
    'warning': 'Omits BC initialization and registration; changes crypto behavior.',
}, indent=2) + '\n')
