plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.composeai.common.io)
  api(project(":daemon:core"))
  api(project(":data-history-core"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-history-connector",
    displayName = "Compose Preview - History Data Product Connector",
    description = "Daemon-side history data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
