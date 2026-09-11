#!/usr/bin/env python3
"""Build an experimental jar constructing BC concurrently and joining before registration."""
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
bc, = [p for p in classpath if '/org.bouncycastle/bcprov-' in p]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
subprocess.run([str(args.jdk / 'bin/javac'), '-cp', asm + ':' + bc, '-d', str(output),
    str(Path(__file__).with_name('AsyncBouncyCastle.java')),
    str(Path(__file__).with_name('AsyncBouncyCastleFactory.java'))], check=True)
jar = output / 'async-bc.jar'
subprocess.run([str(args.jdk / 'bin/java'), '-cp', str(output) + ':' + asm,
    'AsyncBouncyCastle', robolectric, str(jar),
    str(output / 'org/robolectric/android/internal/AsyncBouncyCastleFactory.class')], check=True)
patched = output / 'classpath.txt'
patched.write_text('\n'.join(str(jar) if p == robolectric else p for p in classpath) + '\n')
flags = ['-XX:TieredStopAtLevel=1', '-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30']
(output / 'matrix.json').write_text(json.dumps([
    {'name': 'baseline', 'classpath': str(args.classpath.resolve()), 'jvmArgs': flags},
    {'name': 'async-bc', 'classpath': str(patched), 'jvmArgs': flags},
], indent=2) + '\n')
(output / 'inputs.json').write_text(json.dumps({
    'robolectric': robolectric,
    'robolectricSha256': hashlib.sha256(Path(robolectric).read_bytes()).hexdigest(),
    'asm': asm,
    'warning': 'Experimental concurrent construction; waits before the original BC registration.',
}, indent=2) + '\n')
