plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
}

dependencies { testImplementation(libs.junit) }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-pseudolocale-core",
    displayName = "Compose Preview - Pseudolocale Core",
    description =
      "Pure-JVM accent / bidi pseudolocale text transforms (en-XA, ar-XB) — no Android or Compose dependency.",
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
