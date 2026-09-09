// compose-preview-daemon — the renderers, the data extractors and the daemon that hosts them.
// Extracted from yschimke/compose-ai-tools (docs/design/DAEMON_SPLIT.md). Module paths are the
// ones the modules had there, so history and the published coordinates are unchanged.

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Why this file is shaped the way it is — the lanes it can switch, the build cache policy, the
// module layout — is docs/build-scripts/SETTINGS.md. Comments here state the live constraint only.
// Per-project conventions (ktfmt, googleStyle, the history-gate system property) are applied by
// each module via `plugins { id("composeai.base-conventions") }`, never from the root build.
// docs/build-scripts/SETTINGS.md#base-conventions

// Snapshot probe for the SDK compatibility matrix's snapshot cells (run from compose-ai-tools):
// render at SDK 37 against a Robolectric snapshot.
// docs/build-scripts/SETTINGS.md#robolectric-snapshots
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull

// Which line the three Remote Compose groups (`androidx.compose.remote`,
// `androidx.wear.compose.remote`, `androidx.glance.wear`) resolve from: `release` (default, the
// alpha coordinates pinned in `gradle/libs.versions.toml`) or `snapshot`
// (`-Pcomposeai.remoteCompose=snapshot`, androidx-main post-submit).
//
// CONSTRAINT: the whole trio moves together. They only work when built against the same
// `remote-creation*`, so the mode flips all three keys at once and one group must never straddle
// the two lines. docs/build-scripts/SETTINGS.md#remote-compose-lane
val remoteComposeLine =
  providers.gradleProperty("composeai.remoteCompose").orElse("release").get().trim().lowercase()

require(remoteComposeLine == "release" || remoteComposeLine == "snapshot") {
  "composeai.remoteCompose must be 'release' or 'snapshot', was '$remoteComposeLine'"
}

val useRemoteComposeSnapshot = remoteComposeLine == "snapshot"

// androidx-main post-submit build the Remote Compose / Glance Wear artifacts resolve from when
// `composeai.remoteCompose=snapshot`. Bump this one line to move all three groups to a newer
// snapshot; build ids age out of androidx.dev after a few weeks, so if the artifacts 404 pick a
// fresh one from https://androidx.dev/snapshots/builds.
val androidxSnapshotBuildId = "16155060"

dependencyResolutionManagement {
  // PREFER_PROJECT exists solely so the Kotlin wasmJs toolchain's plugin-owned Node.js
  // distribution repository stays usable; dependency repositories still belong here.
  // docs/build-scripts/SETTINGS.md#repositories-mode
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
    // All three Remote Compose groups come from ONE build id, group-scoped and snapshots-only, so
    // the trio can't skew and nothing else can drift onto an unreviewed snapshot.
    // docs/build-scripts/SETTINGS.md#remote-compose-lane
    if (useRemoteComposeSnapshot) {
      maven("https://androidx.dev/snapshots/builds/$androidxSnapshotBuildId/artifacts/repository") {
        name = "androidxSnapshots"
        content {
          includeGroupByRegex("androidx\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.wear\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.glance\\.wear.*")
        }
        mavenContent { snapshotsOnly() }
      }
    }
    if (matrixRobolectricVersion?.endsWith("-SNAPSHOT") == true) {
      maven("https://central.sonatype.com/repository/maven-snapshots/") {
        name = "robolectric-snapshots-central"
        content { includeGroup("org.robolectric") }
      }
      maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "robolectric-snapshots-oss"
        content { includeGroup("org.robolectric") }
      }
    }
  }

  // Snapshot mode rewrites the three version refs in place, so the TOML keeps exactly one set of
  // coordinates — the released ones. docs/build-scripts/SETTINGS.md#catalog-override
  if (useRemoteComposeSnapshot) {
    versionCatalogs {
      // `create`, not `named` — `named` fails here, and `create("libs")` returns the builder with
      // the TOML already imported, so these three lines override three versions and nothing else.
      create("libs") {
        version("compose-remote", "1.0.0-SNAPSHOT")
        version("wear-compose-remote", "1.0.0-SNAPSHOT")
        version("glance-wear", "1.0.0-SNAPSHOT")
      }
    }
  }
}

// BuildFetch remote Gradle build cache, complementing the local one. Writes are restricted to
// trusted CI builds (ON_CI=true on main); PRs and developer machines are read-only, and the gate is
// value-based so an explicit ON_CI=false stays read-only. Token resolution order and the rest of
// the policy: docs/build-scripts/SETTINGS.md#build-cache
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Non-blank view of a single env var / gradle property: trims and drops empties so a present-but-
// empty source (an unset secret CI still exports) never shadows a later fallback and never enables
// the cache with an empty credential.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull

// TEMPORARY (issue #2824): kill switch for the BuildFetch remote cache — two entries are stored
// truncated at rest and Gradle treats the short read as FATAL, so any build resolving either key
// dies. Skip the remote until BuildFetch evicts them; the local cache stays on regardless.
// TO REVERT: delete this flag + the `composeai.remoteCache` line in gradle.properties.
// docs/build-scripts/SETTINGS.md#remote-cache-kill-switch
val remoteCacheDisabled =
  providers.gradleProperty("composeai.remoteCache").orElse("on").get().trim().lowercase() == "off"

buildCache {
  // The local cache stays ON everywhere, including on the trusted main runs that push — it
  // suppresses the redundant pushes, not the useful ones, so every trusted run can contribute.
  // Don't gate this on push again. docs/build-scripts/SETTINGS.md#local-cache-always-on
  local { isEnabled = true }
  remote<HttpBuildCache> {
    url = uri("https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle/")

    credentials {
      username = "token-auth"
      password = cacheToken
    }

    isPush = onCi && !remoteCacheDisabled
    // TEMPORARY (#2824): `!remoteCacheDisabled` skips the cache holding the truncated entries.
    isEnabled = cacheToken != null && !remoteCacheDisabled
  }
}


include(":daemon-client")
project(":daemon-client").projectDir = file("daemon/client")
include(":daemon:android")
include(":daemon:core")
include(":daemon:desktop")
include(":daemon:harness")
include(":data-a11y-connector")
project(":data-a11y-connector").projectDir = file("data/a11y/connector")
include(":data-a11y-core")
project(":data-a11y-core").projectDir = file("data/a11y/core")
include(":data-a11y-hierarchy-android")
project(":data-a11y-hierarchy-android").projectDir = file("data/a11y/hierarchy-android")
include(":data-ambient-connector")
project(":data-ambient-connector").projectDir = file("data/ambient/connector")
include(":data-ambient-core")
project(":data-ambient-core").projectDir = file("data/ambient/core")
include(":data-deviceframe-connector")
project(":data-deviceframe-connector").projectDir = file("data/deviceframe/connector")
include(":data-deviceframe-core")
project(":data-deviceframe-core").projectDir = file("data/deviceframe/core")
include(":data-displayfilter-connector")
project(":data-displayfilter-connector").projectDir = file("data/displayfilter/connector")
include(":data-displayfilter-core")
project(":data-displayfilter-core").projectDir = file("data/displayfilter/core")
include(":data-focus-connector")
project(":data-focus-connector").projectDir = file("data/focus/connector")
include(":data-focus-connector-desktop")
project(":data-focus-connector-desktop").projectDir = file("data/focus/connector-desktop")
include(":data-focus-core")
project(":data-focus-core").projectDir = file("data/focus/core")
include(":data-fonts-connector")
project(":data-fonts-connector").projectDir = file("data/fonts/connector")
include(":data-fonts-core")
project(":data-fonts-core").projectDir = file("data/fonts/core")
include(":data-fonts-google")
project(":data-fonts-google").projectDir = file("data/fonts/google")
include(":data-gestures-connector")
project(":data-gestures-connector").projectDir = file("data/gestures/connector")
include(":data-gestures-core")
project(":data-gestures-core").projectDir = file("data/gestures/core")
include(":data-gestures-robolectric-stubs")
project(":data-gestures-robolectric-stubs").projectDir = file("data/gestures/robolectric-stubs")
include(":data-glimmer-environment-connector")
project(":data-glimmer-environment-connector").projectDir = file("data/glimmer-environment/connector")
include(":data-history-connector")
project(":data-history-connector").projectDir = file("data/history/connector")
include(":data-history-core")
project(":data-history-core").projectDir = file("data/history/core")
include(":data-keyboard-band")
project(":data-keyboard-band").projectDir = file("data/keyboard/band")
include(":data-keyboard-connector")
project(":data-keyboard-connector").projectDir = file("data/keyboard/connector")
include(":data-keyboard-connector-desktop")
project(":data-keyboard-connector-desktop").projectDir = file("data/keyboard/connector-desktop")
include(":data-keyboard-core")
project(":data-keyboard-core").projectDir = file("data/keyboard/core")
include(":data-launcher-widget-connector")
project(":data-launcher-widget-connector").projectDir = file("data/launcher-widget/connector")
include(":data-layoutinspector-connector")
project(":data-layoutinspector-connector").projectDir = file("data/layoutinspector/connector")
include(":data-motion-core")
project(":data-motion-core").projectDir = file("data/motion/core")
include(":data-navigation-connector")
project(":data-navigation-connector").projectDir = file("data/navigation/connector")
include(":data-navigation-core")
project(":data-navigation-core").projectDir = file("data/navigation/core")
include(":data-permissions-connector")
project(":data-permissions-connector").projectDir = file("data/permissions/connector")
include(":data-permissions-core")
project(":data-permissions-core").projectDir = file("data/permissions/core")
include(":data-preview-overrides-connector")
project(":data-preview-overrides-connector").projectDir = file("data/preview-overrides/connector")
include(":data-preview-overrides-runtime")
project(":data-preview-overrides-runtime").projectDir = file("data/preview-overrides/runtime")
include(":data-pseudolocale-connector")
project(":data-pseudolocale-connector").projectDir = file("data/pseudolocale/connector")
include(":data-pseudolocale-connector-desktop")
project(":data-pseudolocale-connector-desktop").projectDir = file("data/pseudolocale/connector-desktop")
include(":data-pseudolocale-core")
project(":data-pseudolocale-core").projectDir = file("data/pseudolocale/core")
include(":data-recomposition-connector")
project(":data-recomposition-connector").projectDir = file("data/recomposition/connector")
include(":data-recomposition-core")
project(":data-recomposition-core").projectDir = file("data/recomposition/core")
include(":data-remotecompose-connector")
project(":data-remotecompose-connector").projectDir = file("data/remotecompose/connector")
include(":data-remotecompose-core")
project(":data-remotecompose-core").projectDir = file("data/remotecompose/core")
include(":data-render-compose")
project(":data-render-compose").projectDir = file("data/render/compose")
include(":data-render-connector")
project(":data-render-connector").projectDir = file("data/render/connector")
include(":data-resources-connector")
project(":data-resources-connector").projectDir = file("data/resources/connector")
include(":data-resources-core")
project(":data-resources-core").projectDir = file("data/resources/core")
include(":data-scroll-android")
project(":data-scroll-android").projectDir = file("data/scroll/android")
include(":data-scroll-connector")
project(":data-scroll-connector").projectDir = file("data/scroll/connector")
include(":data-scroll-core")
project(":data-scroll-core").projectDir = file("data/scroll/core")
include(":data-strings-connector")
project(":data-strings-connector").projectDir = file("data/strings/connector")
include(":data-strings-core")
project(":data-strings-core").projectDir = file("data/strings/core")
include(":data-theme-connector")
project(":data-theme-connector").projectDir = file("data/theme/connector")
include(":data-touch-overlay-connector")
project(":data-touch-overlay-connector").projectDir = file("data/touch-overlay/connector")
include(":data-uiautomator-connector")
project(":data-uiautomator-connector").projectDir = file("data/uiautomator/connector")
include(":data-uiautomator-core")
project(":data-uiautomator-core").projectDir = file("data/uiautomator/core")
include(":data-uiautomator-hierarchy-android")
project(":data-uiautomator-hierarchy-android").projectDir = file("data/uiautomator/hierarchy-android")
include(":data-wallpaper-connector")
project(":data-wallpaper-connector").projectDir = file("data/wallpaper/connector")
include(":data-wallpaper-core")
project(":data-wallpaper-core").projectDir = file("data/wallpaper/core")
include(":lottie-preview-runtime")
project(":lottie-preview-runtime").projectDir = file("runtimes/lottie")
include(":preview-annotations")
project(":preview-annotations").projectDir = file("api/preview-annotations")
include(":preview-data-api")
project(":preview-data-api").projectDir = file("api/preview-data-api")
include(":renderer-android")
project(":renderer-android").projectDir = file("renderers/android")
include(":renderer-desktop")
project(":renderer-desktop").projectDir = file("renderers/desktop")
include(":renderer-xr-client")
project(":renderer-xr-client").projectDir = file("renderers/xr-client")
include(":slot-preview-runtime")
project(":slot-preview-runtime").projectDir = file("runtimes/slots")
include(":svg-preview-runtime")
project(":svg-preview-runtime").projectDir = file("runtimes/svg")

// Sidecar packaging: the `compose-preview-android-daemon-<v>.zip` and desktop daemon tarball the
// CLI and the preview-server image download. Lives here rather than in the CLI (where it was
// assembled before the split) because it packages this build's outputs.
include(":distribution")

rootProject.name = "compose-preview-daemon"

// Project paths carrying ktfmt, handed to the root build's `ktfmtCheckAll` / `ktfmtFormatAll`
// aggregate tasks through a system property. The channel must stay closure-free under Isolated
// Projects.
val ktfmtProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    // Only projects with a build script apply `composeai.base-conventions`, so container projects
    // like `:daemon` own no ktfmt task and are skipped.
    if (descriptor.buildFile.exists()) add(descriptor.path)
    descriptor.children.forEach(::visit)
  }
  // The root can't apply `composeai.base-conventions` (it would leak to every subproject), so it
  // carries no ktfmt and is left out.
  rootProject.children.forEach(::visit)
}

System.setProperty("composeai.ktfmtProjectPaths", ktfmtProjectPaths.joinToString(","))
