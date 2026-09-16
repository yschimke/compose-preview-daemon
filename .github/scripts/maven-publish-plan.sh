#!/usr/bin/env bash
# Decide which modules a release actually has to publish.
#
# Every release used to publish all 69 coordinates at the tag, whether or not a byte of them had
# changed. Measured over v3.0.0..v3.5.0 — 17 intervals, 1207 module-publications — 67% of that was
# a version-bumped rebuild of identical code, against an org-wide Maven Central file-count limit
# (yschimke/compose-ai-tools#4772).
#
# A module is published when:
#
#   1. a file under it changed since the tag IT last published at (not since the last release —
#      a module skipped for three releases is compared against its own baseline, so nothing is
#      ever missed by a gap), or
#   2. a module it depends on is being published, or
#   3. a shared build input changed, which can move every artifact at once.
#
# Rule 2 is what keeps the POMs honest, and it is deliberately coarser than it needs to be. A
# published POM names its project dependencies at *their* `project.version`, so a module may only
# be skipped while everything it depends on is also skipped; otherwise it would name a sibling
# version that was never uploaded — exactly the break compose-ai-tools shipped in v2.2.1 and paid
# for in yschimke/wear-m3-catalog#350. Propagating on any change rather than only on an ABI change
# costs about 6 percentage points (73% -> 67%) and needs no assumption about binary compatibility;
# tighten it only when the modules carry ABI dumps to prove the surface held.
#
# Every uncertainty resolves to "publish". Central refuses a second upload of a version, so an
# unnecessary publish costs quota while a wrongly-skipped one is unrepairable.
#
# Usage: maven-publish-plan.sh --head <ref> [--manifest <path>]
# Output: one artifact id per line, on stdout. Diagnostics go to stderr.
set -euo pipefail

HEAD_REF=""
MANIFEST="publishing-manifest.json"
while [ $# -gt 0 ]; do
  case "$1" in
    --head) HEAD_REF="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HEAD_REF" ] || { echo "--head is required" >&2; exit 2; }
[ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 2; }

python3 - "$HEAD_REF" "$MANIFEST" <<'PY'
import json, re, subprocess, sys, collections

head, manifest_path = sys.argv[1], sys.argv[2]

def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout

settings = open("settings.gradle.kts", encoding="utf-8").read()
dirs = dict(re.findall(r'project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)', settings))
paths = re.findall(r'^include\("(:[^"]+)"\)', settings, re.M)

modules = {}   # artifactId -> directory
deps = {}      # artifactId -> [artifactId]
path_to_id = {}
for p in paths:
    d = dirs.get(p, p.lstrip(":").replace(":", "/"))
    try:
        text = open(d + "/build.gradle.kts", encoding="utf-8").read()
    except OSError:
        continue
    if 'composeai.maven-publishing")' not in text:
        continue
    aid = p.lstrip(":").replace(":", "-")
    modules[aid] = d
    path_to_id[p] = aid
    deps[aid] = [m.lstrip(":").replace(":", "-")
                 for m in re.findall(r'project\("(:[^"]+)"\)', text)]
deps = {a: [d for d in ds if d in modules] for a, ds in deps.items()}

recorded = json.load(open(manifest_path, encoding="utf-8"))["modules"]

# A shared build input can change any artifact, so it opens the gate for everything.
SHARED = re.compile(r"^(build-logic/|gradle/|gradlew|settings\.gradle\.kts$|build\.gradle\.kts$)")

def changed_since(version, directory):
    """Did `directory` move between the tag for `version` and head?"""
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        print(f"  {directory}: no tag {tag}; publishing", file=sys.stderr)
        return True
    out = git("diff", "--name-only", f"{tag}..{head}", "--", directory)
    return bool(out.strip())

shared_changed = False
for version in sorted(set(recorded.values())):
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        shared_changed = True
        break
    files = [f for f in git("diff", "--name-only", f"{tag}..{head}").split("\n") if f]
    if any(SHARED.match(f) for f in files):
        shared_changed = True
        break

if shared_changed:
    print("  a shared build input changed; publishing every module", file=sys.stderr)
    for aid in sorted(modules):
        print(aid)
    sys.exit(0)

dirty = set()
for aid, directory in modules.items():
    if aid not in recorded:
        print(f"  {aid}: not in the manifest; publishing", file=sys.stderr)
        dirty.add(aid)
    elif changed_since(recorded[aid], directory):
        dirty.add(aid)

# Rule 2: anything depending on a dirty module is dirty too, transitively.
rev = collections.defaultdict(set)
for aid, ds in deps.items():
    for d in ds:
        rev[d].add(aid)
stack = list(dirty)
while stack:
    m = stack.pop()
    for r in rev.get(m, ()):
        if r not in dirty:
            dirty.add(r)
            stack.append(r)

print(f"  {len(dirty)} of {len(modules)} modules publish", file=sys.stderr)
for aid in sorted(dirty):
    print(aid)
PY
