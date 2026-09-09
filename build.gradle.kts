plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  // ktfmt is applied and configured on every project by `ComposeAiBaseConventionsPlugin`
  // (build-logic) — declaring it here too would put a second ktfmt on a different classloader.
  // The Maven publishing plugin is loaded into the root scope so :renderer-android and
  // :daemon:android share its ClassLoader (its MavenCentralBuildService cannot be shared across
  // sibling classloaders).
  alias(libs.plugins.maven.publish) apply false
}

// The per-project conventions live in `ComposeAiBaseConventionsPlugin` (build-logic), applied by
// each module via `plugins { id("composeai.base-conventions") }`. Isolated Projects forbids the
// root build reaching across project boundaries, so nothing is configured from here.

// `./gradlew ktfmtCheck` already fans out to every project that applies the plugin. The aggregate
// tasks exist so CI has one task name; the ktfmt-carrying project paths are gathered in
// `settings.gradle.kts` and handed over via a system property, which stays Isolated-Projects clean.
val ktfmtProjectPaths =
  providers.systemProperty("composeai.ktfmtProjectPaths").get().split(",")

tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across every project."
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtCheck") }
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across every project."
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtFormat") }
}

// The publish task list `release.yml` runs. One version line: every published module in this
// build releases at the tag. (compose-ai-tools split its `data/` modules onto a second line to
// avoid re-uploading 58 unchanged artifacts per release; here they are the bulk of the build and
// change with the renderers, so one train is the honest shape until measured otherwise.)
val printPublishTasks by
  tasks.registering {
    group = "publishing"
    description = "Print the publish task path for each published module."
    notCompatibleWithConfigurationCache("Inspects the project tree at execution time")
    val rootDirPath = rootDir
    val rows =
      subprojects
        .filter { it.plugins.hasPlugin("composeai.maven-publishing") }
        .map { p ->
          "${p.path}:publishAndReleaseToMavenCentral" to
            p.projectDir.relativeTo(rootDirPath).invariantSeparatorsPath
        }
    doLast { rows.sortedBy { it.first }.forEach { (task, dir) -> println("$task\t$dir") } }
  }
