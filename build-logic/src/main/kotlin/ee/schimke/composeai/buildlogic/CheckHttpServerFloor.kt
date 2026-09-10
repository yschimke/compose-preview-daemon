package ee.schimke.composeai.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Fails when an HTTP server engine reaches a production runtime classpath in the daemon build. */
abstract class CheckHttpServerFloor : DefaultTask() {

  /** `<configuration>\t<group>:<module>` entries, transitives included. */
  @get:Input abstract val resolvedModules: ListProperty<String>

  /** Coordinate prefixes that identify an embeddable HTTP server engine. */
  @get:Input abstract val serverPrefixes: ListProperty<String>

  @TaskAction
  fun checkFloor() {
    val prefixes = serverPrefixes.get()
    val offenders =
      resolvedModules
        .get()
        .map { entry -> entry.substringBefore('\t') to entry.substringAfter('\t') }
        .filter { (_, module) -> prefixes.any { module.startsWith(it) } }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))

    check(offenders.isEmpty()) {
      "An HTTP server engine reached ${path.substringBeforeLast(':')}'s production runtime " +
        "classpath: " +
        offenders.joinToString(", ") { (configuration, module) ->
          "$module [$configuration]"
        } +
        ". compose-preview-daemon is stdio-only; HTTP serving belongs in " +
        "compose-preview-server. See docs/design/DAEMON_SPLIT.md."
    }
  }

  companion object {
    /** Prefixes cover server-engine swaps without banning HTTP clients. */
    val serverPrefixes: List<String> =
      listOf("io.ktor:ktor-server", "org.eclipse.jetty:", "io.undertow:")
  }
}
