package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Fails when a tools- or server-owned artifact reaches a daemon production runtime. */
abstract class CheckDependencyOwnership : DefaultTask() {

  /** `<configuration>\t<group>:<module>` entries from resolved production runtime classpaths. */
  @get:Input abstract val resolvedModules: ListProperty<String>

  @TaskAction
  fun checkOwnership() {
    val offenders =
      resolvedModules
        .get()
        .map { entry -> entry.substringBefore('\t') to entry.substringAfter('\t') }
        .filter { (_, module) -> DependencyOwnership.isForbidden(module) }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))

    check(offenders.isEmpty()) {
      val details = offenders.joinToString(", ") { (configuration, module) ->
        "$module [$configuration]"
      }
      "A compose-ai-tools or compose-preview-server artifact reached ${path.substringBeforeLast(':')}" +
        "'s production runtime classpath: $details. compose-preview-daemon may depend only on " +
        "projects in this build and the explicitly owned lower-layer coordinates from " +
        "compose-preview-contracts. See docs/design/DAEMON_SPLIT.md."
    }
  }
}

/** Repository ownership policy, separate from Gradle resolution so it can be unit tested. */
internal object DependencyOwnership {
  const val COMPOSE_AI_GROUP: String = Contracts.GROUP

  /**
   * Exact coordinates owned by the lower-layer compose-preview-contracts repository: its modules
   * and the BOM that versions them. The BOM is a platform component, so it shows up on a resolved
   * runtime classpath as a component of its own (it carries no classes).
   */
  val contractModules: Set<String> = Contracts.modules + Contracts.BOM

  /**
   * Project components are daemon-owned and never enter this function. Every external module in
   * the shared Maven group must be an exact lower-layer contract; all other group members are an
   * upward dependency into tools/server, including future artifacts whose names are not known.
   */
  fun isForbidden(module: String): Boolean =
    module.startsWith("$COMPOSE_AI_GROUP:") && module !in contractModules

  /**
   * Production runtime coverage policy:
   *
   * - JVM: `runtimeClasspath`;
   * - Kotlin Multiplatform JVM: `jvmRuntimeClasspath`;
   * - Android application/library variants: every non-test `*RuntimeClasspath`.
   *
   * Test, lint and tool classpaths are intentionally excluded: the ownership rule describes what
   * ships. Android has no fixed variant set, so matching every production variant avoids silently
   * dropping custom build types.
   */
  fun isProductionRuntimeClasspath(name: String): Boolean {
    if (name == "runtimeClasspath" || name == "jvmRuntimeClasspath") return true
    if (!name.endsWith("RuntimeClasspath")) return false

    val lower = name.lowercase()
    return listOf("test", "androidtest", "unittest", "lint", "screenshot").none(lower::contains)
  }
}
