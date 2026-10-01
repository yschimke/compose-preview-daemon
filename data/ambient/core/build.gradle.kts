plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon-protocol` (compose-preview-contracts) carries the AmbientStateOverride enum the payload
  // mirrors. MCP clients in other languages already consume the protocol module; pulling
  // `data-ambient-core` adds the ambient payload schema alongside.
  api(libs.composeai.daemon.protocol)
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-ambient-core",
    displayName = "Compose Preview - Ambient Data Product Core",
    description = "Shared Wear OS ambient-mode data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
