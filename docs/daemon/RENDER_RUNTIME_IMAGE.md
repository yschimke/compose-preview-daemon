# The render-runtime base image

`ghcr.io/yschimke/compose-preview-render-runtime` is the container base everything that runs this
repository's render daemons builds `FROM`: today the compose-preview-server images, and a
standalone UI-builder image that no longer needs the server to get these layers. It carries the JDK,
a minimal Android SDK, this release's two daemon sidecars, the Skiko native they need, a
prefetched Robolectric runtime and a baked font cache. It does not carry a server, a CLI or a
process to run.

| | |
| --- | --- |
| Source | [`distribution/image/Dockerfile`](../../distribution/image/Dockerfile) (final stage `runtime`) |
| Tags | `<daemon version>` (e.g. `3.28.2`, no `v`) and `latest` |
| Built by | [`render-runtime-image.yml`](../../.github/workflows/render-runtime-image.yml), called by `release.yml` on every release; `workflow_dispatch` with `daemon_version` rebuilds one |
| Platforms | `linux/amd64` only. The sidecars are arch-neutral and the Dockerfile picks the arm64 Skiko native, but the Android SDK build-tools it installs are x86-64 Linux binaries |
| Guard | [`distribution/image/test-render-runtime-image.sh`](../../distribution/image/test-render-runtime-image.sh), run in CI and before each image build |

## The layout contract

Consumers name these paths in their own Dockerfiles and JVM flags, so they are a contract: a
change here is a breaking change for every image built on top, and the guard script fails on any of
them going missing.

| Path | What | Comes from |
| --- | --- | --- |
| `/usr/local/bin/install-linux-font-fallbacks` | installs packages plus the deterministic Noto fallback set; already run with `libfreetype6 libfontconfig1 libgl1 libglx-mesa0 libgl1-mesa-dri curl ca-certificates git jq` | copied from compose-preview-server `scripts/` |
| `/opt/android-sdk` | `cmdline-tools/latest`, `platforms/<androidPlatform>`, `build-tools/<androidBuildTools>` | `sdkmanager` |
| `/opt/lib-daemon-android` | the Android (Robolectric) daemon classpath, no `android.jar` (the launcher adds it from `$ANDROID_HOME`) | `compose-preview-android-daemon-<v>.zip` |
| `/opt/lib-daemon-desktop`, `/opt/lib-renderer` | the desktop (Compose Multiplatform) daemon and the Skiko renderer it links, no Skiko natives | `compose-preview-desktop-daemon-<v>.tar.gz` |
| `/opt/lib-skiko` | `skiko-awt-runtime-<skikoPlatform>-<version>.jar`, the version read off `lib-daemon-desktop`'s `skiko-awt` | Maven Central |
| `/usr/local/bin/check-skiko-pairing.sh` | `check-skiko-pairing.sh /opt <skikoPlatform>`: every Skiko-backed classpath under `/opt` loads a native matching its bindings | copied from compose-preview-server `deploy/image/` |
| `/root/.m2/repository/org/robolectric/android-all-instrumented/<androidAllVersion>/` | Robolectric's instrumented android-all, so a first sandbox boot does not download ~200 MB. Best-effort: absent if the fetch failed, and the daemon then fetches at runtime | Maven Central |
| `/opt/font-cache/fonts` | `<slug>-400.ttf` for each `FONT_PREWARM_FAMILIES` entry, byte-identical to what the Android renderer's `GoogleFontInterceptor` writes | `prewarm-fonts.sh` |
| `/usr/local/bin/prewarm-fonts.sh` | the same script, for consumers that bake extra families | copied from compose-preview-server `deploy/image/` |
| `/opt/compose-preview-render-runtime.json` | the manifest below | written at build time |

Environment: `LIBGL_ALWAYS_SOFTWARE=1`, `ANDROID_HOME=/opt/android-sdk`,
`ANDROID_SDK_ROOT=/opt/android-sdk`. Nothing else is set: **no `JAVA_TOOL_OPTIONS`** (consumers own
their JVM flags, including the `-Dcomposeai.cli.lib*Dir=/opt/…` sysprops that point at the
directories above) and **no `ENTRYPOINT` or `CMD`** of this image's own (the
`eclipse-temurin` base's are inherited unchanged; set yours).

The font cache sits at `/opt/font-cache/fonts` rather than `~/.cache/composeai/fonts` because
consumers typically mount a volume at the latter, and a volume only inherits image content when it
is first created; copy the missing faces across at boot.

### The manifest

```json
{
  "schema": "compose-preview/render-runtime/v1",
  "daemonVersion": "3.28.2",
  "androidPlatform": "android-36",
  "androidBuildTools": "35.0.0",
  "androidAllVersion": "15-robolectric-14663667-2-i7",
  "skikoPlatform": "linux-x64"
}
```

Read it rather than re-deriving versions from directory listings. A new field is a compatible
change; renaming or removing one, or changing a path above, bumps `schema`.

OCI labels: `org.opencontainers.image.version` (the daemon version), `.source`
(`https://github.com/yschimke/compose-preview-daemon`) and `.revision` (the release tag's commit).

## What is deliberately not here

The layer rule ([`AGENTS.md`](../../AGENTS.md)) says this repository may not reach compose-ai-tools
or the server, and a base image is no exception. So none of these are in it, and the guard fails if
the Dockerfile ever references them outside a comment:

- compose-ai-tools' CLI sidecars: `lib-rcjvm/` (the `cmp-jvm` Remote Compose player), `lib-bta/`
  (the playground compiler), `lib-simulator/` and `lib-dex2jar/` (the `dwf-apk` watch-face lane);
- anything from the compose-preview-server tarball, including `lib-rc-player-desktop/` and the
  `window-core` jars it adds to `lib-renderer/`.

A consumer adds those on top. A directory that carries its own `skiko-awt` needs its own native,
staged with the same read-the-version-off-the-jar logic as `/opt/lib-skiko`, and the consumer
re-runs `check-skiko-pairing.sh /opt <platform>` afterwards: the script checks the `lib-rcjvm` and
`compose-preview-server/lib-rc-player-desktop` lanes only when those directories exist, so it
passes on this image and keeps guarding the consumer's.

## Consuming it

```dockerfile
ARG DAEMON_VERSION
FROM ghcr.io/yschimke/compose-preview-render-runtime:${DAEMON_VERSION}
# …add your own sidecars and app, then:
RUN check-skiko-pairing.sh /opt "$(jq -r .skikoPlatform /opt/compose-preview-render-runtime.json)"
ENV JAVA_TOOL_OPTIONS="-Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android -Dcomposeai.cli.libDaemonDesktopDir=/opt/lib-daemon-desktop -Dcomposeai.cli.libRendererDir=/opt/lib-renderer -Dcomposeai.cli.skikoDir=/opt/lib-skiko"
ENTRYPOINT ["/your/entrypoint.sh"]
```

Pin the daemon version your app's `composeai-preview-daemon` catalog entry names, so the sidecars
match the daemon client you link.

## Keeping it current

- **Robolectric.** `ANDROID_ALL_VERSION` is the coordinate Robolectric resolves for the daemon's
  default sandbox SDK (`RobolectricHost.ANDROID_SDK`, 35). It is specific to the Robolectric
  release: 4.18's `DefaultSdkProvider` maps SDK 35 to android-all `15` build `14663667-2`, and
  `DefaultSdkProvider$DefaultSdk` appends `i7`, so the coordinate is
  `15-robolectric-14663667-2-i7`; 4.16/4.17 used `13954326`. The Dockerfile records the version
  it was verified against (`# verified-robolectric:`), and the guard fails when
  `gradle/libs.versions.toml` moves `robolectric` past it. Re-read those two classes in the new
  jar (`javap -c`), update both lines, and the guard goes green.
- **The copied scripts.** `install-linux-font-fallbacks.sh`, `check-skiko-pairing.sh` and
  `prewarm-fonts.sh` came from compose-preview-server at c372f39 (v3.192.0) and say so in their
  headers. This copy is now the upstream for images built on this base; the font family list
  still mirrors `PixelSystemFontAliases.ALIASES` in `:renderer-android`, extend both together.
- **Fonts and licensing.** The Dockerfile's `fonts` stage carries the redistribution note for
  `Google Sans Flex`: the clearance is for this project's deployment and is not inherited by a fork.

## Building it locally

```
cp ~/Downloads/compose-preview-*-daemon-3.28.2.* distribution/image/release-assets/   # optional
docker build --build-arg DAEMON_VERSION=3.28.2 -t compose-preview-render-runtime:3.28.2 distribution/image
```

With `release-assets/` empty the build downloads the two archives from the GitHub release. The
uncompressed image is about 3.2 GB (1.2 GB compressed), most of it the Android sidecar, the SDK and
android-all.
