// `:data-preview-overrides-runtime` — the consumer-facing opt-in API for plain-Compose named
// overrides.
// A preview author adds this as `implementation(...)` and wraps editable values in
// `previewOverride*`
// keyed lookups (`previewOverrideString("label", "Tap me")`, `previewOverrideInt("rowCount", 3)`,
// indexed
// per-item knobs for repeated components). Each lookup returns the daemon-seeded value (or the
// author
// default) and records its declaration into the process-static `PreviewOverrideController` so a
// producer
// can enumerate "what is editable" on the preview.
//
// **Kotlin Multiplatform** (jvm + wasmJs) so the lookups are callable from a KMP consumer's
// `commonMain` — a shared catalog whose bodies compile once and render on Android (Robolectric),
// CMP desktop and wasm. The JVM variant is what Android and desktop both resolve (an `androidJvm`
// consumer takes the `jvm` producer), so the split adds a metadata variant without changing what
// any existing JVM consumer gets.
//
// The split runs along the recording seam: `commonMain` carries the *authoring* surface (the
// `previewOverride*` helpers, `PreviewOverrideHost`, `LocalPreviewOverrideHost`), `jvmMain` the
// *recording* one (`PreviewOverrideController`, `ControllerPreviewOverrideHost`) — which is where
// the JVM-only `:data-preview-overrides-core` wire shapes live. `PreviewOverrideOption` and the
// default host binding bridge the two as `expect`/`actual`; the JVM `actual typealias` keeps the
// existing core type, so no JVM consumer sees a new type.
//
// Compose itself is `compileOnly`: the consumer brings its own Compose (androidx OR jetbrains —
// same FQNs), so this artifact never forces a Compose flavour onto a consumer's classpath. Mirrors
// the `compileOnly` Compose pattern in `:data-remotecompose-connector`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  // KGP-multiplatform and the compose-compiler plugin are already on the buildscript classpath via
  // the Compose bundle, so `alias(libs.plugins…)` errors with "already on the classpath with an
  // unknown version" — apply them by id (mirrors `:slot-preview-runtime`).
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // JVM target — what an Android consumer, a desktop (`ImageComposeScene`) render and the daemon
  // connector all resolve. Unnamed `jvm()` so the published artifact carries the conventional
  // `-jvm` classifier: the bytecode is not desktop-specific (an `androidJvm` consumer resolves the
  // `jvm` variant), same call as `:slot-preview-runtime` and `:preview-annotations`. No Android
  // target — one would stamp a `minSdk` floor onto the artifact for no benefit.
  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    // Library only — the wasmJs app lives in the consuming sample.
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      // Compose runtime (`@Composable`, `compositionLocalOf`, `SideEffect`) + UI (`Color`, `Dp`).
      // `compileOnly` — the consumer supplies the matching Compose at runtime, androidx OR
      // jetbrains (same FQNs), so this artifact never forces a Compose flavour onto a classpath.
      compileOnly(libs.jetbrains.compose.runtime)
      compileOnly(libs.jetbrains.compose.ui)
    }
    jvmMain.dependencies {
      // Wire-shape (declaration + product kind) and, transitively, `PreviewOverrideValue`. `api`
      // so a consumer test or the connector can refer to them without a second dependency. JVM
      // only: `:data-preview-overrides-core` is a JVM artifact, and it is only the *recording*
      // side — the common surface is the authoring one.
      api(libs.composeai.data.preview.overrides.core)

      compileOnly(libs.jetbrains.compose.runtime)
      compileOnly(libs.jetbrains.compose.ui)
    }
    jvmTest.dependencies {
      implementation(libs.jetbrains.compose.runtime)
      implementation(libs.jetbrains.compose.ui)
      implementation(libs.junit)
    }
    wasmJsMain.dependencies {
      compileOnly(libs.jetbrains.compose.runtime)
      compileOnly(libs.jetbrains.compose.ui)
    }
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-preview-overrides-runtime",
    displayName = "Compose Preview - Named Override Runtime",
    description =
      "Opt-in consumer API for plain-Compose preview overrides: `previewOverride*` keyed lookups (string/int/float/bool/color/dp, with indexed knobs for repeated components) that a preview uses to expose editable values, resolved against daemon seeds and recorded for the `compose/overrides` data product.",
  )
  inceptionYear.set("2026")
}
