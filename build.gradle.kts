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
// build releases at the tag, and `:bom` releases alongside them describing that set.
//
// (compose-ai-tools split its `data/` modules onto a second line to avoid re-uploading 58
// unchanged artifacts per release; here they are the bulk of the build and change with the
// renderers, so one train is the honest shape until measured otherwise. It has now been measured:
// a path-based `data` | `core` split is worth −28% of module-publications over v3.0.0..v3.5.0,
// while publishing only the modules a release actually changed is worth −67%. The BOM is the
// prerequisite for the second — it is what lets a consumer keep naming one version when the
// modules behind it stop sharing one. See yschimke/compose-ai-tools#4772.)
tasks.register("printPublishTasks") {
    group = "publishing"
    description = "Print the publish task path for each published module."
    notCompatibleWithConfigurationCache("Inspects the project tree at execution time")
    val rootDirPath = rootDir
    // `-Pcomposeai.publishSet` names the modules this release actually has to upload, computed by
    // `.github/scripts/maven-publish-plan.sh`. Absent, every module publishes — the old behaviour,
    // and the right default for a `workflow_dispatch` recovery run where the plan's baseline may
    // not be trustworthy.
    //
    // The BOM indexes a changed coordinate map, not every GitHub release. An empty set leaves every
    // constraint at its already-published version, so publishing a new, identical BOM wastes quota.
    // Absent and empty mean different things and must not be collapsed: absent is "no plan ran,
    // publish everything", empty is "the plan found nothing to publish". Deliberately mirrors
    // `PublishedVersions.parsePublishSet`, which the modules and `:bom` use -- the root build
    // script cannot see build-logic's classes, so this is the one place the rule is restated.
    // `PublishSetParsingTest` pins the two against each other.
    val publishSet =
      providers
        .gradleProperty("composeai.publishSet")
        .orNull
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
    val rows =
      subprojects
        .filter {
          it.plugins.hasPlugin("composeai.maven-publishing") ||
            it.plugins.hasPlugin("composeai.maven-publishing-platform")
        }
        .filter { p ->
          publishSet == null ||
            (p.path == ":bom" && publishSet.isNotEmpty()) ||
            (p.path != ":bom" && p.path.removePrefix(":").replace(':', '-') in publishSet)
        }
        .map { p ->
          "${p.path}:publishAndReleaseToMavenCentral" to
            p.projectDir.relativeTo(rootDirPath).invariantSeparatorsPath
        }
    doLast { rows.sortedBy { it.first }.forEach { (task, dir) -> println("$task\t$dir") } }
  }
