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
# THE BASELINE COMES FROM MAVEN CENTRAL, not from a file in this repository.
#
# It used to be a committed `publishing-manifest.json`, written back after each release by a job
# that opened a pull request. That cache was wrong exactly when it mattered: v3.8.0 published at
# 20:28 and its write-back PR (#143) merged at 22:40, so for over two hours `main` claimed 3.7.0
# while Central served 3.8.0 — and a release cut in that window would have diffed against a stale
# baseline and re-published everything. If the PR had never merged, every later release would have
# done so silently. The PR also had to be approved and merged by a human, and its `chore:` commit
# proposed a release of its own (#142), so every real release dragged an empty one behind it.
#
# `maven-metadata.xml` answers the same question authoritatively and for free:
#
#     <release>3.8.0</release>
#
# A coordinate Central does not know is one that has never published, which resolves to "publish" —
# the same fail-safe as "not in the manifest" did. A network failure resolves the same way: this
# script over-publishes rather than skipping, because an unnecessary publish costs quota while a
# wrongly-skipped one is unrepairable.
#
# Usage: maven-publish-plan.sh --head <ref> [--manifest <path>] [--write-manifest <path>]
#   --manifest        read the baseline from this file instead of Central (offline / testing).
#   --write-manifest  write the resolved baseline here. The Gradle build reads it to give a
#                     skipped module the version it is already published at; the release job
#                     writes it into the workspace and nothing commits it.
# Output: one artifact id per line, on stdout. Diagnostics go to stderr.
set -euo pipefail

HEAD_REF=""
MANIFEST=""
WRITE_MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --head) HEAD_REF="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --write-manifest) WRITE_MANIFEST="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HEAD_REF" ] || { echo "--head is required" >&2; exit 2; }
[ -z "$MANIFEST" ] || [ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 2; }

python3 - "$HEAD_REF" "$MANIFEST" "$WRITE_MANIFEST" <<'PY'
import json, re, subprocess, sys, collections, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

GROUP_PATH = "ee/schimke/composeai"
CENTRAL = "https://repo1.maven.org/maven2"

head, manifest_path, write_manifest_path = sys.argv[1], sys.argv[2], sys.argv[3]

def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout

settings = open("settings.gradle.kts", encoding="utf-8").read()
# `\s*=\s*` rather than a literal " = ": ktfmt wraps a long assignment onto the next line, and a
# regex that missed one fell back to deriving the directory from the project path -- which does not
# exist on disk, so the module vanished from the plan without a word. Found in rc-players, where two
# projects are wrapped; none here is today, which is exactly why it would go unnoticed.
dirs = dict(
    re.findall(r'project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', settings)
)
paths = re.findall(r'^include\("(:[^"]+)"\)', settings, re.M)

modules = {}   # artifactId -> directory
deps = {}      # artifactId -> [artifactId]
path_to_id = {}
for p in paths:
    d = dirs.get(p, p.lstrip(":").replace(":", "/"))
    try:
        text = open(d + "/build.gradle.kts", encoding="utf-8").read()
    except OSError as e:
        # Never skip quietly. A module this script cannot read is a module it cannot classify, and
        # a wrongly-skipped publish is the unrepairable direction -- Central refuses a second
        # upload of a version. Failing here costs a red release job; guessing costs a coordinate
        # that never shipped.
        print(f"  cannot read {p}'s build file at {d}: {e}", file=sys.stderr)
        sys.exit(2)
    if 'composeai.maven-publishing")' not in text:
        continue
    aid = p.lstrip(":").replace(":", "-")
    modules[aid] = d
    path_to_id[p] = aid
    deps[aid] = [m.lstrip(":").replace(":", "-")
                 for m in re.findall(r'project\("(:[^"]+)"\)', text)]
deps = {a: [d for d in ds if d in modules] for a, ds in deps.items()}

def central_release(aid):
    """The newest version of `aid` on Central, or None if it has never published there.

    `<release>` rather than `<latest>`: `latest` can name a snapshot on repositories that carry
    them, and a baseline that is not a real release would diff against a tag that does not exist.
    """
    url = f"{CENTRAL}/{GROUP_PATH}/{aid}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            body = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None  # never published
        print(f"  {aid}: Central said {e.code}; publishing", file=sys.stderr)
        return None
    except Exception as e:  # noqa: BLE001 - any failure resolves to "publish"
        print(f"  {aid}: could not reach Central ({e}); publishing", file=sys.stderr)
        return None
    m = re.search(r"<release>([^<]+)</release>", body)
    return m.group(1) if m else None


if manifest_path:
    recorded = json.load(open(manifest_path, encoding="utf-8"))["modules"]
    print(f"  baseline: {manifest_path} ({len(recorded)} entries)", file=sys.stderr)
else:
    # Eight at a time: 69 sequential round-trips is most of this script's wall clock, and Central
    # serves these as static files.
    with ThreadPoolExecutor(max_workers=8) as pool:
        found = dict(zip(sorted(modules), pool.map(central_release, sorted(modules))))
    recorded = {aid: v for aid, v in found.items() if v}
    print(f"  baseline: Maven Central ({len(recorded)} of {len(modules)} coordinates)",
          file=sys.stderr)

if write_manifest_path:
    with open(write_manifest_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "_comment": "The version each coordinate is published at on Maven Central. "
                            "Resolved at release time by .github/scripts/maven-publish-plan.sh "
                            "and NOT committed - Central is the source of truth.",
                "modules": dict(sorted(recorded.items())),
            },
            f,
            indent=2,
        )
        f.write("\n")

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
        print(f"  {aid}: never published; publishing", file=sys.stderr)
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
