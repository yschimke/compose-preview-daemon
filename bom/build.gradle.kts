plugins {
  // Same pair every published module carries. `base-conventions` is what gives this project its
  // ktfmt task, which the root build's `ktfmtFormatAll` / `ktfmtCheckAll` aggregates expect of
  // every project with a build script — `:distribution` applies it for the same reason.
  id("composeai.base-conventions")
  id("composeai.maven-publishing-platform")
}

// The BOM for everything this repository publishes.
//
// `compose-preview-daemon` publishes 69 coordinates on one version line. A consumer wanting three
// of them has to name three versions and keep them in step; get it wrong and the mismatch surfaces
// as a `NoSuchMethodError` at render time rather than at resolution. Importing this platform
// replaces all of that with one coordinate:
//
//     implementation(platform("ee.schimke.composeai:compose-preview-daemon-bom:<version>"))
//     implementation("ee.schimke.composeai:renderer-android")
//     implementation("ee.schimke.composeai:data-fonts-core")
//
// The constraints are derived, not listed. `settings.gradle.kts` collects every project path whose
// build script applies `composeai.maven-publishing` and hands them over as a system property; the
// artifact id is the path with its separators flattened (`:daemon:core` -> `daemon-core`), which
// `PublishedArtifactIdTest` in build-logic pins against the build files so a module that breaks the
// convention fails the build rather than going missing from the BOM.
//
// Every constraint takes THIS project's version. That is correct only while the repository
// publishes one version line — which is the invariant AGENTS.md states, and the thing the BOM
// exists to let us revisit: once modules publish at the version they last changed at, this is the
// artifact that tells a consumer which versions belong together, and the derivation below grows a
// per-module lookup instead of `project.version`.
val publishedProjectPaths =
  providers.systemProperty("composeai.publishedProjectPaths").get().split(",").filter {
    it.isNotBlank()
  }

dependencies {
  constraints {
    publishedProjectPaths
      .map { path -> path.removePrefix(":").replace(':', '-') }
      .sorted()
      .forEach { artifactId -> api("ee.schimke.composeai:$artifactId:${project.version}") }
  }
}

composeAiPlatformPublishing {
  coordinates(
    artifactId = "compose-preview-daemon-bom",
    displayName = "Compose Preview Daemon - Bill of Materials",
    description =
      "Version constraints for every Compose Preview daemon artifact, so a consumer aligns the " +
        "renderers, the daemon and the data products with one coordinate.",
  )
  inceptionYear.set("2026")
}
