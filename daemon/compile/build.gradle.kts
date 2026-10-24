// In-process Kotlin compilation for the daemon and the playground, over the Kotlin Build Tools API:
// warm compile sessions, incremental runs, class-path snapshots and diagnostic capture.
//
// **Moved here from compose-preview-contracts**, where it was `ee.schimke.composeai:daemon-bta`
// (last release 3.39.0, package `ee.schimke.composeai.daemon.bta`). It went there in #3824, as
// the last of the server's imports from `:daemon:core`, but it is behaviour, not a wire shape: it
// hashes class-path entries, walks directories and drives the compiler. Contracts' rule is "shape,
// never behaviour", and the code it rules out belongs in the layer that executes composables —
// this one.
//
// **A new coordinate and a new package, deliberately.** Republishing `daemon-bta` from this
// repository would put one coordinate on two version lines: contracts already reached 3.39.0, this
// repository is at 3.2x, so Gradle would resolve any graph that still sees a contracts BOM to the
// stale contracts jar without a word. A new coordinate cannot lose that race. The new package also
// ends the split package `ee.schimke.composeai.daemon.bta` shared with compose-ai-tools'
// `:daemon:bta-host`.
//
// The `composeai.daemon.bta.*` system properties keep their names: the Gradle plugin writes them
// into every launch descriptor, so they are wire, and the module's name does not change them.
//
// **The dependency this publishes is the point.** `DiagnosticCollector` implements the Build Tools
// API's `KotlinLogger`, and `BtaCompileSession.compile` takes one; those types are on this module's
// compile ABI, so the BTA is `api` here.
//
// The `impl` side stays out. `kotlin-build-tools-impl` is loaded reflectively at first use through
// `SharedApiClassesClassLoader`, which delegates API lookups up to this classpath — which is why
// the API jar has to be here and the impl jar does not.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-compile`. `:daemon:core` exposes
// it as `api`, so a consumer of `daemon-core` (the preview server's playground) reaches it there.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("composeai.abi-validation")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // `CompileErrorDetail` and `SourceChangeSet` are protocol shapes on this module's own surface.
  api(libs.composeai.daemon.protocol)

  // `FileSystem.SYSTEM` for the benchmark entry point's source scan.
  implementation(libs.okio)

  // `api`, not `implementation` — see the header. These types are in public signatures.
  api("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-compile",
    displayName = "Compose Preview — In-Process Kotlin Compile",
    description =
      "In-process Kotlin compilation over the Build Tools API, as the compose-preview daemon " +
        "and playground drive it: warm compile sessions, incremental runs and diagnostic capture.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // A published surface another repository (the preview server) compiles against, so an
  // implicitly-public declaration is an API decision nobody made.
  explicitApi()
}
