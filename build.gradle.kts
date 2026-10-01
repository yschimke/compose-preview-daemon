plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  // ktfmt is applied and configured on every project by `ComposeAiBaseConventionsPlugin`
  // (build-logic) — declaring it here too would put a second ktfmt on a different classloader.
  // The Maven publishing plugin is loaded into the root scope so :renderer-android and
  // :daemon:android share its ClassLoader (its MavenCentralBuildService cannot be shared across
  // sibling classloaders).
  alias(libs.plugins.maven.publish) apply false
}

// The per-project conventions live in `ComposeAiBaseConventionsPlugin` (build-logic), applied by
// each module via `plugins { id("composeai.base-conventions") }`. Isolated Projects forbids the
// root build reaching across project boundaries, so nothing is configured from here.

// The root's aggregate and release tasks live in `root-tasks.gradle.kts`, not here. This file is a
// shared build input to `.github/scripts/maven-publish-plan.sh` -- the plugins above reach every
// module, so a change to it publishes all of them -- while those tasks decide which tasks run and
// build nothing. Keeping them apart means a change to the release wiring stops re-uploading 69
// unchanged coordinates (v3.8.4 did, for a fix to `printPublishTasks`).
apply(from = "root-tasks.gradle.kts")
