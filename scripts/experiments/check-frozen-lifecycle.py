#!/usr/bin/env python3
"""Run existing interaction/adoption tests with AGP's manifest and resources.

Build :daemon:android:compileDebugUnitTestKotlin and :daemon:android:generateDebugUnitTestConfig
first. Run separately with baseline and patched classpath files; these are correctness
checks, not timing trials. Each selected method runs in a fresh JVM.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath', type=Path, required=True)
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
module = Path(__file__).resolve().parents[2] / 'daemon/android'
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
extra = [output,
    module / 'build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes',
    module / 'build/intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out',
    module / 'src/test/resources']
for path in extra:
    if not path.is_dir():
        parser.error(f'Missing test input: {path}; build the Android unit tests first')
classpath = os.pathsep.join(map(str, extra + [Path(p) for p in args.classpath.read_text().splitlines()]))
subprocess.run([str(args.jdk / 'bin/javac'), '-cp', classpath, '-d', str(output),
    str(Path(__file__).with_name('RunJUnitMethod.java'))], check=True)
checks = [
    ('LivePressRippleTest', 'aLiveClickPaintsPressFeedback'),
    ('AndroidInteractiveSessionTest', 'heldClickToggleSurvivesAcrossInputs'),
    ('RobolectricHostSpareAdoptionTest', 'aReleasedSpareListensAgainAndTheNextHostAdoptsIt'),
]
results = []
for test, method in checks:
    with (output / (test + '.log')).open('w') as log:
        result = subprocess.run([str(args.jdk / 'bin/java'), '-Xmx2g',
            '-XX:TieredStopAtLevel=1', '-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30',
            '-Djava.awt.headless=true', '-Drobolectric.graphicsMode=NATIVE',
            '-Drobolectric.enabledSdks=35', '-Droborazzi.test.record=true', '-cp', classpath,
            'RunJUnitMethod', 'ee.schimke.composeai.daemon.' + test, method],
            cwd=module, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    results.append({'test': test + '#' + method, 'exitCode': result.returncode})
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(results[-1], flush=True)
if any(result['exitCode'] for result in results):
    raise SystemExit(1)
