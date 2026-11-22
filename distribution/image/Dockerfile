# syntax=docker/dockerfile:1.29

# ghcr.io/yschimke/compose-preview-render-runtime — the render runtime base image.
#
# Everything a box needs to run this repository's two render daemons outside a Gradle build, and
# nothing else: a JDK with Skiko's native dependencies, a minimal Android SDK, the released Android
# and desktop daemon sidecars, the host Skiko native paired with the desktop daemon, a prefetched
# Robolectric `android-all-instrumented`, and a baked downloadable-font cache. The preview server
# image and a standalone UI-builder image both build `FROM` it, so neither has to rebuild these
# layers and a UI builder no longer needs the server to get them.
#
# The layout is a CONTRACT other repositories rely on. It is written down in
# docs/daemon/RENDER_RUNTIME_IMAGE.md and pinned by test-render-runtime-image.sh beside this file;
# change all three together.
#
#   /usr/local/bin/install-linux-font-fallbacks   /usr/local/bin/check-skiko-pairing.sh
#   /usr/local/bin/prewarm-fonts.sh               /opt/compose-preview-render-runtime.json
#   /opt/android-sdk                              /opt/lib-daemon-android
#   /opt/lib-daemon-desktop                       /opt/lib-renderer
#   /opt/lib-skiko                                /opt/font-cache/fonts
#   /root/.m2/repository/org/robolectric/android-all-instrumented/<ANDROID_ALL_VERSION>/
#
# Deliberately NOT here (the layer rule: this repository may not reach compose-ai-tools or the
# server): compose-ai-tools' CLI sidecars and anything from the server's tarball. A consumer that
# needs them adds them on top and re-runs `check-skiko-pairing.sh /opt <platform>`, which then
# checks their lanes too. No JAVA_TOOL_OPTIONS and no ENTRYPOINT either: consumers own their JVM
# flags and their process.
#
# Lifted from compose-preview-server's `deploy/image/Dockerfile` (c372f39, v3.192.0) stage for
# stage; the rationale for each stage that is not repeated here is in that file's history.

# Required. The compose-preview-daemon release whose sidecar archives this image carries; the
# image is tagged with it. `render-runtime-image.yml` passes the release tag.
ARG DAEMON_VERSION
# The Android SDK pieces baked into /opt/android-sdk, recorded in the manifest.
ARG ANDROID_SDK_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=35.0.0
# Robolectric's instrumented android-all, prefetched into /root/.m2. The coordinate is
# Robolectric-version + SDK specific. The daemon's default sandbox SDK is
# `RobolectricHost.ANDROID_SDK` = 35, and Robolectric 4.18 (`robolectric` in
# gradle/libs.versions.toml, which the sidecar bundles) maps SDK 35 in `DefaultSdkProvider` to
# android-all 15 / build `14663667-2` with PREINSTRUMENTED_VERSION 7 (the `i7` suffix) — read off
# the 4.18 jar's bytecode. The server's previous default, `15-robolectric-13954326-i7`, is the
# 4.16/4.17 row and is a cache MISS on 4.18. Re-verify (the SDK 35 row in `DefaultSdkProvider` +
# the `i<N>` literal in `DefaultSdkProvider$DefaultSdk`) whenever `robolectric` bumps;
# test-render-runtime-image.sh fails until the marker below names the new version.
# verified-robolectric: 4.18
ARG ANDROID_ALL_VERSION=15-robolectric-14663667-2-i7

# ---------------------------------------------------------------------------
# base — JDK + Skiko's native deps (libGL via libgl1, Mesa software GLX/DRI, fonts; force software
# GL). Same package list as the server's `base` stage; `jq` is for consumers' entrypoints (and
# writes the manifest below), `git` for bundle fetches.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13.1_2-jdk AS base
COPY install-linux-font-fallbacks.sh /usr/local/bin/install-linux-font-fallbacks
RUN COMPOSEAI_CLEAN_APT=1 /usr/local/bin/install-linux-font-fallbacks \
      libfreetype6 libfontconfig1 libgl1 libglx-mesa0 libgl1-mesa-dri \
      curl ca-certificates git jq
ENV LIBGL_ALWAYS_SOFTWARE=1

# ---------------------------------------------------------------------------
# android-sdk — minimal Android SDK: cmdline-tools + one platform's android.jar + build-tools
# (aapt2, for consumers that package resources; no emulator). The Android daemon's launcher adds
# `android.jar` from `$ANDROID_HOME` because the sidecar zip deliberately does not ship it
# (see `StageDaemonAndroidLibs` in distribution/build.gradle.kts).
# ---------------------------------------------------------------------------
FROM base AS android-sdk
ARG ANDROID_SDK_PLATFORM
ARG ANDROID_BUILD_TOOLS
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends unzip; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    curl -fsSL --retry 10 --retry-delay 15 --retry-all-errors -o /tmp/cmdtools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; \
    unzip -q /tmp/cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdtools.zip; \
    yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null; \
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
      "platforms;${ANDROID_SDK_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" >/dev/null; \
    test -f "${ANDROID_HOME}/platforms/${ANDROID_SDK_PLATFORM}/android.jar"

# ---------------------------------------------------------------------------
# android-daemon — the released Android (Robolectric) daemon sidecar → /opt/lib-daemon-android,
# plus Robolectric's instrumented `android-all` in the local Maven cache so the daemon's FIRST
# sandbox boot does not download it (~200 MB).
#
# Automatic releases stage the tag's archive in `release-assets/` (the build context) and can build
# before the GitHub Release is public; a manual build leaves the directory empty and falls back to
# the published asset, with retries for upload propagation.
# ---------------------------------------------------------------------------
FROM base AS android-daemon
ARG DAEMON_VERSION
COPY release-assets/ /tmp/release-assets/
RUN set -eu; \
    test -n "${DAEMON_VERSION}" || { echo "DAEMON_VERSION build arg is required" >&2; exit 1; }; \
    apt-get update && apt-get install -y --no-install-recommends unzip; \
    rm -rf /var/lib/apt/lists/*; \
    local="/tmp/release-assets/compose-preview-android-daemon-${DAEMON_VERSION}.zip"; \
    if [ -f "$local" ]; then \
      cp "$local" /tmp/android-daemon.zip; \
    else \
      curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/android-daemon.zip \
        "https://github.com/yschimke/compose-preview-daemon/releases/download/v${DAEMON_VERSION}/compose-preview-android-daemon-${DAEMON_VERSION}.zip"; \
    fi; \
    unzip -q /tmp/android-daemon.zip -d /opt; \
    rm /tmp/android-daemon.zip; \
    test -d /opt/lib-daemon-android; \
    ls /opt/lib-daemon-android/*.jar >/dev/null
# Prefetch android-all (see ANDROID_ALL_VERSION at the top for how the coordinate was verified) so
# the daemon's first sandbox boot skips the download. Best-effort: the daemon still fetches at
# runtime if this is stale or missing.
ARG ANDROID_ALL_VERSION
# Download to a temp path and move on success. A partial jar left at the real `jarPath()` would be
# worse than no prefetch: Robolectric's MavenArtifactFetcher skips its runtime download when the
# target exists, so a truncated file would boot the daemon with a corrupt android-all instead of
# falling back. On any failure, delete the temp file and let the daemon fetch at runtime.
RUN set -eu; \
    d="/root/.m2/repository/org/robolectric/android-all-instrumented/${ANDROID_ALL_VERSION}"; \
    f="$d/android-all-instrumented-${ANDROID_ALL_VERSION}.jar"; \
    mkdir -p "$d"; \
    if curl -fsSL --retry 10 --retry-delay 15 --retry-all-errors -o "$f.tmp" \
        "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/${ANDROID_ALL_VERSION}/android-all-instrumented-${ANDROID_ALL_VERSION}.jar"; then \
      mv "$f.tmp" "$f"; \
    else \
      rm -f "$f.tmp"; \
      echo "warn: android-all prefetch failed (coordinate stale?); daemon will fetch at runtime"; \
    fi

# ---------------------------------------------------------------------------
# desktop-daemon — the released desktop (Compose Multiplatform / Skiko) daemon tarball →
# /opt/lib-daemon-desktop + /opt/lib-renderer (packed flat), and the host Skiko native paired with
# lib-daemon-desktop's `skiko-awt` → /opt/lib-skiko.
#
# Neither sidecar carries a Skiko NATIVE: `:distribution` drops every `skiko-awt-runtime-*` on
# purpose (`checkSkikoNativePackaging`), because the consumer resolves the pair for its own
# platform. Without one every Skiko-backed lane dies in `Library`'s static initialiser
# (`LibraryLoadException: Cannot find libskiko-linux-x64.so.sha256`), reported to callers as a bare
# `ExceptionInInitializerError: null`. The version is READ OFF the staged `skiko-awt-<version>.jar`
# rather than pinned here, so it cannot drift from the bindings; fetching it is a hard failure,
# because unlike android-all there is no runtime fetch to fall back on. A consumer pointing
# `-Dcomposeai.cli.skikoDir` at /opt/lib-skiko gets it prepended to the desktop classpath.
#
# A consumer that adds another Skiko-backed directory (compose-ai-tools' `lib-rcjvm/`, the server's
# `lib-rc-player-desktop/`) stages that directory's own native with the same `stage_native` logic
# and re-runs `check-skiko-pairing.sh`; the check skips those lanes when the directories are absent,
# as they are here.
# ---------------------------------------------------------------------------
FROM base AS desktop-daemon
ARG DAEMON_VERSION
COPY release-assets/ /tmp/release-assets/
RUN set -eu; \
    test -n "${DAEMON_VERSION}" || { echo "DAEMON_VERSION build arg is required" >&2; exit 1; }; \
    local="/tmp/release-assets/compose-preview-desktop-daemon-${DAEMON_VERSION}.tar.gz"; \
    if [ -f "$local" ]; then \
      cp "$local" /tmp/desktop-daemon.tgz; \
    else \
      curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/desktop-daemon.tgz \
        "https://github.com/yschimke/compose-preview-daemon/releases/download/v${DAEMON_VERSION}/compose-preview-desktop-daemon-${DAEMON_VERSION}.tar.gz"; \
    fi; \
    tar -xzf /tmp/desktop-daemon.tgz -C /opt; \
    rm /tmp/desktop-daemon.tgz; \
    test -d /opt/lib-daemon-desktop; \
    test -d /opt/lib-renderer; \
    ls /opt/lib-daemon-desktop/*.jar >/dev/null; \
    ls /opt/lib-renderer/*.jar >/dev/null
COPY check-skiko-pairing.sh /usr/local/bin/check-skiko-pairing.sh
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) platform=linux-x64 ;; \
      arm64) platform=linux-arm64 ;; \
      *) echo "no Skiko native for $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    stage_native() { \
      awt="$(ls "$1"/skiko-awt-*.jar 2>/dev/null | grep -v '/skiko-awt-runtime-' | head -n 1)"; \
      version="$(basename "$awt" .jar | sed 's/^skiko-awt-//')"; \
      test -n "$version" || return 0; \
      nativeJar="skiko-awt-runtime-${platform}-${version}.jar"; \
      mkdir -p "$2"; \
      curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o "$2/${nativeJar}" \
        "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-${platform}/${version}/${nativeJar}"; \
      jar tf "$2/${nativeJar}" | grep -qx "libskiko-${platform}.so"; \
    }; \
    stage_native /opt/lib-daemon-desktop /opt/lib-skiko; \
    chmod +x /usr/local/bin/check-skiko-pairing.sh; \
    check-skiko-pairing.sh /opt "$platform"; \
    echo "$platform" > /opt/lib-skiko/.platform

# ---------------------------------------------------------------------------
# fonts — prewarm the downloadable-font cache.
#
# The Android renderer resolves `Font(DeviceFontFamilyName("roboto-flex"))` — Wear Material3's type
# scale — by downloading the Google Fonts TTF and seeding it into Robolectric's system font map
# (`PixelSystemFontAliases`). Unresolved slugs fall back to plain Roboto SILENTLY, which is what
# made the live daemon's text differ from the baked catalog PNG on the preview host. Baking the
# faces removes the runtime download entirely: no first-render latency, no dependency on egress to
# fonts.googleapis.com, and the same bytes CI rendered the PNGs with.
#
# LICENSING — this stage REDISTRIBUTES font binaries in a published image, which is a different act
# from the runtime fetch it replaces. All but one family below are in the google/fonts open corpus
# under OFL-1.1 or Apache-2.0, which permit redistribution. `Google Sans Flex` is the exception: the
# CSS2 endpoint serves it, but it is in no license directory of that repo, so its terms can't be
# read off the corpus. It is baked here because the project owner confirmed redistribution is
# cleared for this deployment — if you fork this image, that clearance is NOT inherited, so re-check
# it or drop the family from FONT_PREWARM_FAMILIES. Dropping it only forgoes the baked copy; the
# renderer still fetches it at runtime, exactly as every family did before this stage existed.
#
# The list mirrors `PixelSystemFontAliases.ALIASES`; extend both together.
#
# The cache lands at /opt/font-cache/fonts rather than `~/.cache/composeai/fonts` because consumers
# typically mount a volume at the latter; a volume only inherits image content when first created,
# so a consumer's entrypoint copies the missing faces across on each boot instead.
# ---------------------------------------------------------------------------
FROM base AS fonts
COPY prewarm-fonts.sh /usr/local/bin/prewarm-fonts.sh
ARG FONT_PREWARM_FAMILIES="Roboto|Roboto Flex|Google Sans Flex|Noto Sans|Noto Serif|Noto Sans Mono|Cutive Mono|Coming Soon|Dancing Script|Carrois Gothic SC"
# `IFS='|'` + an unquoted expansion is how the pipe-separated list becomes one argument per family;
# family names contain spaces, so splitting on whitespace would be wrong.
RUN set -eu; \
    chmod +x /usr/local/bin/prewarm-fonts.sh; \
    IFS='|'; \
    /usr/local/bin/prewarm-fonts.sh /opt/font-cache/fonts ${FONT_PREWARM_FAMILIES}

# ---------------------------------------------------------------------------
# runtime — the published image. Carries only the contract paths; no ENTRYPOINT, no CMD, no
# JAVA_TOOL_OPTIONS.
# ---------------------------------------------------------------------------
FROM base AS runtime
ARG DAEMON_VERSION
ARG ANDROID_SDK_PLATFORM
ARG ANDROID_BUILD_TOOLS
ARG ANDROID_ALL_VERSION
# The commit the image was built from; `render-runtime-image.yml` passes the release tag's commit.
ARG REVISION=unknown
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
COPY --from=android-sdk /opt/android-sdk /opt/android-sdk
COPY --from=android-daemon /opt/lib-daemon-android /opt/lib-daemon-android
COPY --from=android-daemon /root/.m2/repository/org/robolectric /root/.m2/repository/org/robolectric
COPY --from=desktop-daemon /opt/lib-daemon-desktop /opt/lib-daemon-desktop
COPY --from=desktop-daemon /opt/lib-renderer /opt/lib-renderer
COPY --from=desktop-daemon /opt/lib-skiko /opt/lib-skiko
COPY --from=desktop-daemon /usr/local/bin/check-skiko-pairing.sh /usr/local/bin/check-skiko-pairing.sh
COPY --from=fonts /opt/font-cache/fonts /opt/font-cache/fonts
COPY --from=fonts /usr/local/bin/prewarm-fonts.sh /usr/local/bin/prewarm-fonts.sh
# The manifest consumers read instead of re-deriving the versions from directory listings. The
# pairing check re-runs against the final tree, so a COPY that dropped the native fails here too.
RUN set -eu; \
    test -n "${DAEMON_VERSION}" || { echo "DAEMON_VERSION build arg is required" >&2; exit 1; }; \
    platform="$(cat /opt/lib-skiko/.platform)"; \
    rm /opt/lib-skiko/.platform; \
    check-skiko-pairing.sh /opt "$platform"; \
    jq -n \
      --arg daemonVersion "${DAEMON_VERSION}" \
      --arg androidPlatform "${ANDROID_SDK_PLATFORM}" \
      --arg androidBuildTools "${ANDROID_BUILD_TOOLS}" \
      --arg androidAllVersion "${ANDROID_ALL_VERSION}" \
      --arg skikoPlatform "${platform}" \
      '{schema: "compose-preview/render-runtime/v1", daemonVersion: $daemonVersion, androidPlatform: $androidPlatform, androidBuildTools: $androidBuildTools, androidAllVersion: $androidAllVersion, skikoPlatform: $skikoPlatform}' \
      > /opt/compose-preview-render-runtime.json; \
    cat /opt/compose-preview-render-runtime.json
LABEL org.opencontainers.image.title="compose-preview-render-runtime" \
      org.opencontainers.image.description="JDK, Android SDK, compose-preview-daemon sidecars, Skiko native and font cache for rendering Compose previews" \
      org.opencontainers.image.version="${DAEMON_VERSION}" \
      org.opencontainers.image.source="https://github.com/yschimke/compose-preview-daemon" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0"
