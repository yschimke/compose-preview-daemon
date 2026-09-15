#!/usr/bin/env bash
# Shared "release-please can parse this commit message" detector.
#
# ONE implementation, THREE callers — the local `commit-msg` hook, the local
# `pre-push` hook, and the PR-layer CI check. Same reasoning as
# `agent-attribution-scan.sh`: a gate that disagrees with itself is worse than
# no gate.
#
# WHY THIS EXISTS
#
# release-please DISCARDS a commit whose message its parser rejects, and the
# run still reports success:
#
#   ❯ commit could not be parsed: 23a3653 fix: serve the variable face …
#   ❯ error message: Error: unexpected token '(' at 5:17, valid tokens [)]
#   ❯ commits: 0
#   ✔ No commits for path: ., skipping
#
# The commit then never reaches the changelog, and when it is the ONLY commit
# since the last release no release is proposed at all — the release pull
# request simply stops moving. Nothing goes red. See issue #115.
#
# WHAT IS REJECTED (deliberately narrow)
#
# A BODY LINE whose first whitespace-delimited token contains a nested `(`.
# The grammar reads `token(` at the start of a line as a Conventional Commits
# SCOPE, and a second `(` inside a scope is not legal — hence the parser's
# "valid tokens [)]".
#
#   `Font(GoogleFont(...))` carries its settings   ← REJECTED (line starts with it)
#   call foo(bar(baz)) now                         ← fine (a word comes first)
#   call foo( bar(baz) ) now                       ← fine (space breaks the token)
#   `foo(bar)` now                                 ← fine (not nested)
#
# The same sentence can pass or fail depending on where it WRAPS, which is why
# this is worth a gate rather than a convention.
#
# A nested scope in the SUBJECT (`fix(a(b)): x`) fails the parser too, but the
# Conventional Commits check in `commit-msg` and `pr-title.yml` already reject
# that shape, so it is not re-litigated here.
#
# MODES
#
#   parser     — authoritative. Uses @conventional-commits/parser, the package
#                release-please itself parses with, when `node` can resolve it.
#   heuristic  — the first-token rule above. Matches the parser on every probe
#                in #115, but it is an approximation and says so.
#
# `--require-parser` fails closed when the parser cannot be resolved, for CI,
# where "the authoritative check silently degraded" is the failure this whole
# script exists to prevent.
#
# Usage:
#   release-please-parse-scan.sh [--text-file <file>]... [--range <rev>]...
#                                [--require-parser]
#
# Exit: 0 clean · 1 a message release-please would drop · 2 detector could not run

set -uo pipefail

text_files=()
ranges=()
require_parser=0

while [ $# -gt 0 ]; do
  case "$1" in
    --text-file)
      [ $# -ge 2 ] || { echo "release-please-parse-scan: --text-file needs a value" >&2; exit 2; }
      text_files+=("$2"); shift 2 ;;
    --range)
      [ $# -ge 2 ] || { echo "release-please-parse-scan: --range needs a value" >&2; exit 2; }
      ranges+=("$2"); shift 2 ;;
    --require-parser)
      require_parser=1; shift ;;
    *)
      echo "release-please-parse-scan: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ "${#text_files[@]}" -eq 0 ] && [ "${#ranges[@]}" -eq 0 ]; then
  echo "release-please-parse-scan: nothing to scan (pass --text-file and/or --range)" >&2
  exit 2
fi

# ── mode selection ────────────────────────────────────────────────────────────
mode="heuristic"
if command -v node >/dev/null 2>&1 &&
   node -e 'require("@conventional-commits/parser")' >/dev/null 2>&1; then
  mode="parser"
fi
if [ "$require_parser" -eq 1 ] && [ "$mode" != "parser" ]; then
  cat >&2 <<'EOF'
release-please-parse-scan: --require-parser was given but
@conventional-commits/parser could not be resolved with node.

This check fails closed rather than silently degrading to the heuristic: a
degraded authoritative gate is the exact failure mode it exists to catch.
Install it next to the checkout, e.g.

  npm install --no-save @conventional-commits/parser
EOF
  exit 2
fi

# ── one message in, verdict out ───────────────────────────────────────────────
# Prints an explanation and returns 1 when release-please would drop $1.
check_message() {
  local label="$1" message="$2"

  if [ "$mode" = "parser" ]; then
    local err
    err="$(printf '%s' "$message" | node -e '
      const {parser} = require("@conventional-commits/parser");
      let s = "";
      process.stdin.on("data", d => s += d);
      process.stdin.on("end", () => {
        try { parser(s.endsWith("\n") ? s : s + "\n"); }
        catch (e) { process.stdout.write(String(e.message).split("\n")[0]); }
      });
    ' 2>/dev/null)"
    [ -z "$err" ] && return 0
    printf '%s\n  %s\n' "$label" "$err" >&2
    return 1
  fi

  # heuristic: a body line whose first whitespace-delimited token holds a nested `(`
  local line first opens
  local in_body=0 lineno=0 bad=0
  while IFS= read -r line; do
    lineno=$((lineno + 1))
    if [ "$in_body" -eq 0 ]; then
      [ -z "$line" ] && in_body=1
      continue
    fi
    first="${line%%[[:space:]]*}"
    opens="${first//[!(]/}"
    if [ "${#opens}" -ge 2 ]; then
      printf '%s\n  line %d starts with %s — a nested "(" in the first token of a\n  body line reads as a Conventional Commits scope and fails the parser.\n' \
        "$label" "$lineno" "$first" >&2
      bad=1
    fi
  done <<< "$message"
  return "$bad"
}

status=0

for f in "${text_files[@]-}"; do
  [ -n "$f" ] || continue
  if [ ! -r "$f" ]; then
    echo "release-please-parse-scan: cannot read $f" >&2
    exit 2
  fi
  check_message "message in $f:" "$(cat "$f")" || status=1
done

for r in "${ranges[@]-}"; do
  [ -n "$r" ] || continue
  while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    check_message "commit $(git log -1 --format='%h %s' "$sha"):" \
      "$(git log -1 --format='%B' "$sha")" || status=1
  done < <(git rev-list --no-merges "$r" 2>/dev/null)
done

if [ "$status" -ne 0 ]; then
  cat >&2 <<EOF

release-please would DISCARD the message(s) above and still report success, so
the change would never reach the changelog — and if it were the only commit
since the last release, no release would be proposed at all (issue #115).

Reword so no body line STARTS with a nested parenthesis. Any leading word is
enough:

  -  \`Font(GoogleFont(...))\` carries its settings in …
  +  The \`Font(GoogleFont(...))\` call carries its settings in …

(detector mode: $mode)
EOF
else
  echo "release-please can parse every message scanned (mode: $mode)."
fi

exit "$status"
