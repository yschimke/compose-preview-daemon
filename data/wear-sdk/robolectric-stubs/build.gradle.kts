// Signature-only copy of the public Wear SDK optional platform APIs exercised by the simulator.
// Generic android.jar excludes these types. This Android library is runtime-only for the daemon
// and must never be packaged into an application's APK.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
}

android {
  namespace = "ee.schimke.composeai.wearsdk.stubs"
  compileSdk = 37
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-wear-sdk-robolectric-stubs",
    displayName = "Compose Preview - Wear SDK Robolectric Stubs",
    description =
      "Daemon-only signatures for optional Wear SDK APIs, used with Robolectric shadows by Wear simulations.",
  )
  inceptionYear.set("2026")
}
