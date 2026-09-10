package ee.schimke.composeai.buildlogic

import com.ncorti.ktfmt.gradle.KtfmtExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Conventions that previously lived in the root build's `allprojects {}` block. Isolated Projects
 * forbids a project configuring its siblings, so they're pushed down to each project: this plugin
 * is applied to every project from `settings.gradle.kts` via the IP-safe
 * `gradle.lifecycle.beforeProject` hook.
 *
 * Living in `build-logic` lets the ktfmt extension be configured with its real type
 * (`extensions.configure<KtfmtExtension>`) rather than reflectively — the settings script can't
 * import the type, but this convention plugin's classpath already carries both ktfmt and the Kotlin
 * Gradle plugin it links against.
 */
class ComposeAiBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.ncorti.ktfmt.gradle")
    project.extensions.configure<KtfmtExtension>("ktfmt") { googleStyle() }

    // History recording is on by default in the daemon (`HistoryFeature.ENABLED`); tests set it
    // explicitly so they stay pinned if that default ever changes. Git-provenance caching is
    // disabled in tests (`gitProvenanceTtlMs=0`) so per-render provenance stays fresh and
    // deterministic — production uses the default TTL to collapse render-burst git fetches.
    project.tasks.withType<Test>().configureEach {
      systemProperty("composeai.history.enabled", "true")
      systemProperty("composeai.history.gitProvenanceTtlMs", "0")
    }

    // Build-cache salt. A Gradle cache key is the hash of a task's declared inputs, so an extra
    // declared input property lets us move every Kotlin compilation to a fresh set of keys by
    // bumping one number in gradle.properties.
    //
    // This exists because a remote-cache entry can go bad at rest: in July 2026 the BuildFetch
    // entry for `:daemon:core:compileKotlin` was stored truncated, and every consumer that
    // resolved that key died in the *load* ("Failed to load cache entry cc7964dd…: Could not load
    // from remote cache: Unexpected end of ZLIB input stream") — before the task could execute,
    // so nothing ever pushed a replacement. PR runs are read-only (see settings.gradle.kts) and
    // main runs aborted at the same point, so it could not self-heal; it blocked every build that
    // touched `:daemon:core` until the key changed. BuildFetch documents no way to evict a single
    // entry (LRU under storage pressure is the only documented eviction), so a salt we control is
    // the escape hatch.
    //
    // Bumping it orphans the poisoned key rather than deleting it: the next pushing main run
    // executes the affected tasks and stores clean entries under the new keys, and everything else
    // reads those. Cost is one cold main build; the stale entries age out via LRU. Prefer asking
    // BuildFetch to evict the specific entry when that's an option — this is the lever for
    // when it isn't.
    //
    // Applied here rather than in `composeai.kotlin-conventions` deliberately: base-conventions
    // is the plugin *every* module applies, and `:daemon:core` — the module that was actually
    // poisoned — does not apply the Kotlin conventions plugin.
    val cacheSalt = project.providers.gradleProperty("composeai.cacheSalt").orElse("0")
    project.tasks.withType<KotlinCompilationTask<*>>().configureEach {
      inputs.property("composeai.cacheSalt", cacheSalt)
    }

    registerDependencyChecks(project)
  }

  /**
   * Every component on this project's resolved runtime classpath as `<group>:<name>`.
   *
   * Resolved identity rather than declared dependencies: an artifact arriving through another POM
   * is the case that matters and the case a build-file scan misses. Shared by both boundary checks
   * so they cannot disagree about what is on the classpath.
   */
  private fun resolvedRuntimeModules(configuration: Configuration) =
    configuration.incoming.resolutionResult.rootComponent.map {
      configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component ->
          (component.id as? ModuleComponentIdentifier)?.let {
            "${it.group}:${it.module}"
          }
        }
        .map { module -> "${configuration.name}\t$module" }
    }

  /** Registers both checks on every module and feeds them every covered production configuration. */
  private fun registerDependencyChecks(project: Project) {
    val ownership =
      project.tasks.register<CheckDependencyOwnership>("checkDependencyOwnership") {
        description = "Fails if a tools- or server-owned artifact reaches production runtime."
        group = "verification"
        resolvedModules.convention(emptyList())
      }
    val httpFloor =
      project.tasks.register<CheckHttpServerFloor>("checkHttpServerFloor") {
        description = "Fails if an HTTP server engine reaches production runtime."
        group = "verification"
        resolvedModules.convention(emptyList())
        serverPrefixes.set(CheckHttpServerFloor.serverPrefixes)
      }

    project.configurations.configureEach {
      if (isCanBeResolved && DependencyOwnership.isProductionRuntimeClasspath(name)) {
        val modules = resolvedRuntimeModules(this)
        ownership.configure { resolvedModules.addAll(modules) }
        httpFloor.configure { resolvedModules.addAll(modules) }
      }
    }

    project.plugins.withId("base") {
      project.tasks.named("check") {
        dependsOn(ownership)
        dependsOn(httpFloor)
      }
    }
  }
}
