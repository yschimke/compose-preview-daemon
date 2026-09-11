#!/usr/bin/env python3
"""Patch an isolated Robolectric jar for the jar-backed sandbox/CDS experiment.

The input classpath may be the baseline or frozen-binding classpath. The classloader
bytecode must match the measured Robolectric 4.17-beta-4 implementation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath', type=Path, required=True)
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
paths = args.classpath.read_text().splitlines()
asm, = [p for p in paths if '/org.ow2.asm/asm/' in p]
sandboxes = []
for path in paths:
    if path.endswith('.jar'):
        with zipfile.ZipFile(path) as jar:
            if 'org/robolectric/internal/bytecode/SandboxClassLoader.class' in jar.namelist():
                sandboxes.append(path)
sandbox, = sandboxes
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
subprocess.run([str(args.jdk / 'bin/javac'), '-cp', asm, '-d', str(output),
    str(Path(__file__).with_name('JarBackedSandboxClasses.java'))], check=True)
patched = output / 'sandbox.jar'
subprocess.run([str(args.jdk / 'bin/java'), '-cp', str(output) + ':' + asm,
    'JarBackedSandboxClasses', sandbox, str(patched)], check=True)
(output / 'classpath.txt').write_text('\n'.join(str(patched) if p == sandbox else p for p in paths) + '\n')
(output / 'inputs.json').write_text(json.dumps({'sandbox': sandbox,
    'sandboxSha256': hashlib.sha256(Path(sandbox).read_bytes()).hexdigest(),
    'asm': asm}, indent=2) + '\n')
