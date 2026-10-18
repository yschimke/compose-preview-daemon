"""Exercise the actual release workflow Publish shell with a local Gradle stub."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/release.yml'


def publish_script():
    section = WORKFLOW.read_text().split('      - name: Publish\n', 1)[1]
    lines = section.split('        run: |\n', 1)[1].splitlines()
    script = []
    for line in lines:
        if line.strip() and not line.startswith('          '):
            break
        script.append(line[10:] if line else '')
    return '\n'.join(script)


class ReleasePublishTest(unittest.TestCase):
    def run_publish(self, *, planned, plan_status=0, plan=''):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = root / 'gradlew'
            gradle.write_text('''#!/bin/bash
case "$*" in
  *printPublishTasks*) printf '%s' "$TEST_PLAN"; exit "$TEST_PLAN_STATUS" ;;
  *) printf '%s\\n' "$*" > published ;;
esac
''')
            gradle.chmod(0o755)
            output = root / 'output'
            output.touch()
            env = {**os.environ, 'PLANNED': str(planned).lower(), 'PUBLISH_SET': '',
                   'TEST_PLAN_STATUS': str(plan_status), 'TEST_PLAN': plan,
                   'GITHUB_OUTPUT': str(output)}
            result = subprocess.run(['bash', '-c', publish_script()], cwd=root, env=env,
                                    capture_output=True, text=True)
            return result, output.read_text(), (root / 'published').exists()

    def test_failed_planner_is_not_a_successful_empty_release(self):
        result, output, published = self.run_publish(planned=True, plan_status=7)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertNotIn('skipping Central publication', result.stdout)
        self.assertFalse(published)
        self.assertNotIn('uploaded=true', output)

    def test_successful_empty_plan_skips_publication(self):
        result, output, published = self.run_publish(planned=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('skipping Central publication', result.stdout)
        self.assertFalse(published)
        self.assertNotIn('uploaded=true', output)

    def test_empty_recovery_plan_still_fails(self):
        result, _, published = self.run_publish(planned=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(published)

    def test_nonempty_plan_publishes(self):
        result, output, published = self.run_publish(planned=True, plan=':module:publish\tmetadata\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(published)
        self.assertIn('uploaded=true', output)


if __name__ == '__main__':
    unittest.main()
