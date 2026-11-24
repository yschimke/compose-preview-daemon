plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon-protocol` (compose-preview-contracts) carries `PreviewOverrides`,
  // `RemoteComposeOverride`, `RemoteNamedValue`, and `RemoteHostAction` (the wire-shape the
  // connector's planner reads from). Mirrors `:data-permissions-core` / `:data-keyboard-core` — the
  // kind constant + payload class lives on a tiny JVM module so MCP clients in other languages can
  // depend on the Remote Compose product identity without dragging in the connector, Compose, or
  // the alpha `androidx.compose.remote.*` artifacts.
  api(libs.composeai.daemon.protocol)
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-remotecompose-core",
    displayName = "Compose Preview - Remote Compose Data Product Core",
    description =
      "Shared Remote Compose data-product model classes for Compose Preview (named-value store, host action queue, profile).",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — every declaration states its visibility, every public one its return type.
  // This module is a published contract an extracted preview server compiles against across a repo
  // boundary (#3824), so an implicitly-public declaration is an API decision nobody made.
  //
  // Everything here was already public by default and is already in a shipped ABI, so the
  // annotations preserve the existing surface rather than changing it — narrowing any of these to
  // `internal` would be a breaking change and is deliberately not part of this pass.
  explicitApi()
}
