plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.composeai.common.io)
  // Filter matrices, ColorMatrix4x5, PostCaptureProcessor implementation, shared constants.
  api(project(":data-displayfilter-core"))
  // DataProductRegistry and the DataFetchResult / DataProductCapability wire types. Re-exported so
  // daemon:android can depend on data-displayfilter-connector alone and still pick up
  // DataProductRegistry transitively, as every other connector does.
  api(project(":daemon-connector-api"))
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-displayfilter-connector",
    displayName = "Compose Preview - Display Filter Data Product Connector",
    description =
      "Daemon-side display-filter data-product connector: writes per-render variant PNGs and a manifest JSON, and serves the displayfilter/variants kind via the compose-preview daemon's data/* JSON-RPC surface.",
  )
  inceptionYear.set("2026")
}
