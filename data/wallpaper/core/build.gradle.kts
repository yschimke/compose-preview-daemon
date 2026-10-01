plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon-protocol` (compose-preview-contracts) is the protocol definitions module.
  // WallpaperPayload references the protocol-level WallpaperPaletteStyle enum, so the core schema
  // module needs it on the classpath. MCP clients in other languages already consume the protocol
  // module; pulling `data-wallpaper-core` adds the wallpaper payload schema alongside.
  api(libs.composeai.daemon.protocol)
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-wallpaper-core",
    displayName = "Compose Preview - Wallpaper Data Product Core",
    description = "Shared wallpaper data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
