#!/usr/bin/env bash
# Guard: the render-runtime Dockerfile keeps the layout other repositories build FROM, and stays
# inside this repository's layer.
#
# Static, no Docker needed. The compose-preview-server image and the UI-builder image read these
# paths by name (sysprops such as `-Dcomposeai.cli.libDaemonDesktopDir=/opt/lib-daemon-desktop`), so
# a renamed or dropped directory does not fail anything here: it fails a consumer's lane at runtime,
# silently, as a baked-PNG fallback. The contract is docs/daemon/RENDER_RUNTIME_IMAGE.md.
#
#   test-render-runtime-image.sh            checks distribution/image/Dockerfile
#   DOCKERFILE=<path> test-render-runtime-image.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../.." && pwd)"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"
catalog="${CATALOG:-${repo}/gradle/libs.versions.toml}"

failures=0
fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}
pass() { echo "PASS: $*"; }

# The Dockerfile without comment lines, so prose that names a forbidden asset (to explain why it is
# absent) does not count as a reference.
code() { grep -vE '^[[:space:]]*#' "$1"; }

# Lines of the final `runtime` stage only (from its FROM to the next FROM or EOF).
runtime_stage() {
  awk '/^FROM /{ in_rt = ($0 ~ / AS runtime$/) } in_rt' "$1"
}

check_dockerfile() {
  local df="$1" body rt
  [[ -f "${df}" ]] || {
    fail "missing ${df}"
    return
  }
  body="$(code "${df}")"
  rt="$(runtime_stage "${df}" | grep -vE '^[[:space:]]*#')"

  grep -qxF 'FROM eclipse-temurin:21.0.13.1_2-jdk AS base' <<<"${body}" ||
    fail "base must be FROM eclipse-temurin:21.0.13.1_2-jdk"
  grep -qxF 'FROM base AS runtime' <<<"${body}" || fail "no 'FROM base AS runtime' stage"
  [[ "$(grep -E '^FROM ' <<<"${body}" | tail -n 1)" == 'FROM base AS runtime' ]] ||
    fail "the runtime stage must be the last (default) target"

  grep -qF 'COPY install-linux-font-fallbacks.sh /usr/local/bin/install-linux-font-fallbacks' \
    <<<"${body}" || fail "install-linux-font-fallbacks is not installed at /usr/local/bin"
  # The server's `base` stage package list, as one normalised string.
  local pkgs
  pkgs="$(awk '/install-linux-font-fallbacks \\$/{f=1; next} f{print; if ($0 !~ /\\$/) exit}' \
    "${df}" | tr -d '\\' | tr -s ' \n' ' ' | sed 's/^ //; s/ $//')"
  [[ "${pkgs}" == 'libfreetype6 libfontconfig1 libgl1 libglx-mesa0 libgl1-mesa-dri curl ca-certificates git jq' ]] ||
    fail "base package list drifted from the server's: '${pkgs}'"
  grep -qE '^ENV LIBGL_ALWAYS_SOFTWARE=1' <<<"${body}" || fail "ENV LIBGL_ALWAYS_SOFTWARE=1 missing"

  grep -qxF 'ARG ANDROID_SDK_PLATFORM=android-36' <<<"${body}" || fail "ARG ANDROID_SDK_PLATFORM=android-36"
  grep -qxF 'ARG ANDROID_BUILD_TOOLS=35.0.0' <<<"${body}" || fail "ARG ANDROID_BUILD_TOOLS=35.0.0"
  grep -qE '^ARG ANDROID_ALL_VERSION=[0-9]+-robolectric-[0-9-]+-i[0-9]+$' <<<"${body}" ||
    fail "ARG ANDROID_ALL_VERSION=<sdk>-robolectric-<build>-i<N> missing"
  grep -qF 'ARG FONT_PREWARM_FAMILIES="Roboto|Roboto Flex|Google Sans Flex|' <<<"${body}" ||
    fail "ARG FONT_PREWARM_FAMILIES missing or reordered"
  grep -qE '^ENV ANDROID_HOME=/opt/android-sdk \\$' <<<"${rt}" &&
    grep -qE 'ANDROID_SDK_ROOT=/opt/android-sdk' <<<"${rt}" ||
    fail "runtime stage must set ANDROID_HOME and ANDROID_SDK_ROOT to /opt/android-sdk"

  # Every contract path must be put into the RUNTIME stage, not merely built in some stage.
  local path
  for path in /opt/android-sdk /opt/lib-daemon-android /opt/lib-daemon-desktop /opt/lib-renderer \
    /opt/lib-skiko /opt/font-cache/fonts /root/.m2/repository/org/robolectric \
    /usr/local/bin/check-skiko-pairing.sh /usr/local/bin/prewarm-fonts.sh; do
    grep -qE "^COPY --from=[a-z0-9-]+ ${path} ${path}$" <<<"${rt}" ||
      fail "runtime stage does not COPY --from a build stage to ${path}"
  done
  grep -qF '> /opt/compose-preview-render-runtime.json' <<<"${rt}" ||
    fail "runtime stage does not write /opt/compose-preview-render-runtime.json"
  local key
  for key in '"compose-preview/render-runtime/v1"' daemonVersion androidPlatform androidBuildTools \
    androidAllVersion skikoPlatform; do
    grep -qF -- "${key}" <<<"${rt}" || fail "manifest is missing ${key}"
  done
  for key in version source revision; do
    grep -qE "org\.opencontainers\.image\.${key}=" <<<"${rt}" || fail "OCI label image.${key} missing"
  done
  grep -qF 'check-skiko-pairing.sh /opt "$platform"' <<<"${body}" ||
    fail "the Skiko pairing check is no longer run"
  grep -qF 'stage_native /opt/lib-daemon-desktop /opt/lib-skiko' <<<"${body}" ||
    fail "lib-skiko is no longer staged from lib-daemon-desktop's skiko-awt"

  # Release assets: this repository's two archives, local-first with the public fallback.
  grep -qF 'COPY release-assets/ /tmp/release-assets/' <<<"${body}" || fail "release-assets/ is not staged"
  grep -qF 'compose-preview-android-daemon-${DAEMON_VERSION}.zip' <<<"${body}" ||
    fail "the Android daemon zip is not the source of lib-daemon-android"
  grep -qF 'compose-preview-desktop-daemon-${DAEMON_VERSION}.tar.gz' <<<"${body}" ||
    fail "the desktop daemon tarball is not the source of lib-daemon-desktop / lib-renderer"

  # Layer rule: nothing from compose-ai-tools or the server, and consumers own flags and process.
  local forbidden
  for forbidden in lib-rcjvm lib-bta lib-simulator lib-dex2jar compose-ai-tools \
    'compose-preview-server' 'compose-preview-${' TOOLS_VERSION SERVER_VERSION JAVA_TOOL_OPTIONS; do
    if grep -qF -- "${forbidden}" <<<"${body}"; then
      fail "Dockerfile references '${forbidden}' outside a comment: $(grep -nF -- "${forbidden}" <<<"${body}" | head -n 1)"
    fi
  done
  if grep -qE '^(ENTRYPOINT|CMD)( |\[)' <<<"${body}"; then
    fail "the base image must not set ENTRYPOINT or CMD"
  fi
  # Each release download names this repository, never another one.
  if grep -oE 'github\.com/[^/ "]+/[^/ "]+/releases' <<<"${body}" | grep -vqx 'github.com/yschimke/compose-preview-daemon/releases'; then
    fail "a release download names a repository other than yschimke/compose-preview-daemon"
  fi

  # The redistribution caveat travels with the font list.
  grep -qF '`Google Sans Flex` is the exception' "${df}" &&
    grep -qF 'that clearance is NOT inherited' "${df}" ||
    fail "the Google Sans Flex licensing comment is missing"
}

check_dockerfile "${dockerfile}"
[[ "${failures}" -eq 0 ]] && pass "Dockerfile keeps the render-runtime contract and the layer rule"

# The android-all coordinate is only right for the Robolectric it was read off. A bump moves the
# SDK row (4.17 → 4.18 moved SDK 35 from build 13954326 to 14663667-2), and a stale coordinate is a
# silent ~200 MB download on every cold box, so make the bump say so.
verified="$(sed -n 's/^# verified-robolectric: //p' "${dockerfile}")"
actual="$(sed -n 's/^robolectric = "\([^"]*\)"$/\1/p' "${catalog}")"
if [[ -z "${actual}" ]]; then
  fail "no robolectric version in ${catalog}"
elif [[ "${verified}" != "${actual}" ]]; then
  fail "ANDROID_ALL_VERSION was verified against Robolectric '${verified}', the build uses '${actual}'."
  echo "      Read the SDK 35 row of DefaultSdkProvider in robolectric-${actual}.jar, update" >&2
  echo "      ANDROID_ALL_VERSION and the '# verified-robolectric:' marker together." >&2
else
  pass "ANDROID_ALL_VERSION verified against Robolectric ${actual}"
fi

# check-skiko-pairing.sh must accept this image's own tree — no lib-rcjvm, no server — and still
# reject a mismatched or missing native.
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
layout() {
  local dir="${tmp}/$1" jar
  shift
  for jar in "$@"; do
    mkdir -p "${dir}/$(dirname "${jar}")"
    : >"${dir}/${jar}"
  done
  echo "${dir}"
}
pairing() {
  local want="$1" name="$2" root="$3" status=0 out
  out="$(bash "${here}/check-skiko-pairing.sh" "${root}" linux-x64 2>&1)" || status=$?
  if [[ "${want}" == pass && "${status}" -ne 0 ]] || [[ "${want}" == fail && "${status}" -eq 0 ]]; then
    fail "check-skiko-pairing: ${name}: expected ${want}, got exit ${status}:"$'\n'"${out}"
  else
    pass "check-skiko-pairing: ${name} (${want})"
  fi
}
desktop=(lib-daemon-desktop/daemon-desktop.jar lib-daemon-desktop/skiko-awt-0.144.6.jar
  lib-renderer/renderer-desktop.jar lib-renderer/skiko-awt-0.144.6.jar)
pairing pass "base image layout, no lib-rcjvm or server dirs" \
  "$(layout ok "${desktop[@]}" lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar)"
pairing fail "native for a different Skiko" \
  "$(layout mismatch "${desktop[@]}" lib-skiko/skiko-awt-runtime-linux-x64-0.150.1.jar)"
pairing fail "no native staged" "$(layout missing "${desktop[@]}")"

# Self-test: a Dockerfile that lifts compose-ai-tools' sidecars, drops a contract path and sets an
# ENTRYPOINT must be rejected, or the checks above prove nothing.
bad="${tmp}/Dockerfile.bad"
sed -e 's|^COPY --from=desktop-daemon /opt/lib-skiko /opt/lib-skiko$|COPY --from=desktop-daemon /opt/lib-rcjvm /opt/lib-rcjvm|' \
  "${dockerfile}" >"${bad}"
echo 'ENTRYPOINT ["/bin/sh"]' >>"${bad}"
before="${failures}"
check_dockerfile "${bad}" 2>"${tmp}/bad.err"
caught=$((failures - before))
failures="${before}"
for want in '/opt/lib-skiko' "'lib-rcjvm'" 'ENTRYPOINT'; do
  grep -qF -- "${want}" "${tmp}/bad.err" || fail "self-test: a broken Dockerfile was not caught on ${want}"
done
[[ "${caught}" -ge 3 ]] && pass "self-test: a Dockerfile breaking the contract is rejected (${caught} findings)"

[[ "${failures}" -eq 0 ]] || {
  echo "${failures} check(s) failed" >&2
  exit 1
}
echo "PASS: render-runtime image contract"
