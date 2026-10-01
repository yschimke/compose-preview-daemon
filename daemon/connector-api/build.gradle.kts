// The connector SPI: the types a data-product connector implements and reads, split out of
// `:daemon:core` so a connector compiles against them and nothing else.
//
// Before this module every connector depended on `:daemon:core` for `DataProductRegistry`,
// `FileBackedDataProductRegistry`, `RenderResult` and `PreviewOverrideExtension`, and through it on
// the JSON-RPC server, the history store and the render host. The publish plan
// (`.github/scripts/maven-publish-plan.sh`) republishes every module that depends on a changed one,
// so any `daemon-core` change re-uploaded about 40 coordinates — v3.9.1 and v3.9.2 each changed
// five modules and published 40. Connectors that only need the SPI now depend on this module, and a
// `daemon-core` change reaches them only when this surface changes.
//
// The package is `ee.schimke.composeai.daemon`, unchanged: every type kept its fully-qualified
// name, so nothing that compiled against `daemon-core` breaks, and `:daemon:core` exposes this
// module as `api`. The package was already shared with the connector artifacts, which declare their
// products in it.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-connector-api`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // The SPI is stated in protocol and render-context types (`DataFetchResult`,
  // `DataProductCapability`, `PreviewOverrides`, `PreviewContext`, `RenderTrace`,
  // `DataExtension`), so they stay on its compile ABI, as they were on `daemon-core`'s.
  api(libs.composeai.daemon.protocol)
  api(libs.composeai.data.render.core)
  api(libs.kotlinx.serialization.json)

  implementation(libs.composeai.common.io)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-connector-api",
    displayName = "Compose Preview — Daemon Connector API",
    description =
      "The data-product connector SPI of the compose-preview daemon: DataProductRegistry, FileBackedDataProductRegistry, RenderResult and the preview-override extension. Pre-1.0.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // Same contract discipline as `:daemon:core`, which these declarations came from.
  explicitApi()
}
