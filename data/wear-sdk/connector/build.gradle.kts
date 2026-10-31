// Robolectric behavior for the optional Wear SDK signatures. This module deliberately models only
// operations Compose Preview can safely guarantee: virtual haptic identifiers and an empty tile
// registry. Everything else keeps the explicit unsupported placeholder behavior.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ee.schimke.composeai.wearsdk.connector"
  compileSdk = 37
}

dependencies {
  compileOnly(project(":data-wear-sdk-robolectric-stubs"))
  testImplementation(project(":data-wear-sdk-robolectric-stubs"))
  compileOnly(libs.robolectric)
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-wear-sdk-connector",
    displayName = "Compose Preview - Wear SDK Simulator Connector",
    description =
      "Daemon-side Robolectric shadows for the safe optional Wear SDK behavior needed by Wear simulations.",
  )
  inceptionYear.set("2026")
}
