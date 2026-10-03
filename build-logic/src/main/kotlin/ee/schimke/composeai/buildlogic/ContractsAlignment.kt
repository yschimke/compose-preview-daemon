package ee.schimke.composeai.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * The coordinates this repository consumes from the lower-layer compose-preview-contracts
 * repository, and the BOM that versions them.
 *
 * Shared by [alignContractsThroughBom], which decides what reaches the published POMs, and by
 * [DependencyOwnership], which only verifies the runtime classpath. It lives here rather than in
 * `CheckDependencyOwnership.kt` because the publish plan treats that file as verification-only: an
 * edit to this list changes artifacts, so it must sit in a file the plan counts as a build input.
 */
internal object Contracts {
  const val GROUP: String = "ee.schimke.composeai"

  /** The contracts BOM. Published at every contracts tag; pins each module at its real version. */
  const val BOM: String = "$GROUP:compose-preview-contracts-bom"

  /** The version-catalog alias of [BOM]. */
  const val BOM_ALIAS: String = "composeai-contracts-bom"

  /** Exact coordinates owned by compose-preview-contracts. */
  val modules: Set<String> =
    setOf(
      "$GROUP:agent-grant-protocol",
      "$GROUP:common-io",
      "$GROUP:daemon-bta",
      "$GROUP:daemon-devices",
      "$GROUP:daemon-protocol",
      "$GROUP:data-layoutinspector-core",
      "$GROUP:data-preview-overrides-core",
      "$GROUP:data-render-core",
      "$GROUP:data-theme-core",
      "$GROUP:parity-issues-protocol",
      "$GROUP:ui-builder-protocol",
    )
}

/**
 * Adds `platform(compose-preview-contracts-bom)` to every configuration that declares a contracts
 * module, so the versionless catalog entries resolve through the BOM.
 *
 * Per declaring configuration rather than once on `api`: the entries are used from `api`,
 * `implementation`, `compileOnly`, `testImplementation`, Kotlin Multiplatform source sets
 * (`jvmMainApi`, `jvmTestImplementation`) and Android variants alike, and a platform on `api` alone
 * never reaches `compileOnly` or a KMP source set that does not inherit from it. Attaching the BOM
 * to the configuration that names the module covers every one of those, and it follows the module
 * into the published POM and Gradle module metadata as an imported BOM, exactly where it is needed.
 *
 * Why a BOM rather than a version: contracts releases publish only the modules they changed, so a
 * tag is not a version every contract module has. Pinning a module at a tag it skipped is a 404.
 */
internal fun Project.alignContractsThroughBom() {
  val catalog =
    extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")?.orElse(null)
      ?: return
  val bom = catalog.findLibrary(Contracts.BOM_ALIAS).orElse(null) ?: return

  // One bucket holding the platform, which a declaring configuration then extends. Through
  // `extendsFrom` rather than by adding the platform to the declaring configuration itself: the
  // callback below runs inside that configuration's dependency collection, which Gradle does not
  // let a callback mutate, while its hierarchy it does. Inheritance reaches resolution and the
  // published variants (`apiElements` / `runtimeElements` and their KMP / Android equivalents)
  // alike, so the POM imports the BOM wherever a contracts module is published.
  // Realized here, before the `configureEach` below: creating it from inside that callback would
  // add to the container mid-iteration.
  val platformBucket =
    configurations
      .dependencyScope(CONTRACTS_PLATFORM_CONFIGURATION) {
        // Eager: `platform(Provider)` through `addLater` drops the platform category attribute and
        // asks for the BOM as a library, which no variant of it satisfies.
        dependencies.add(project.dependencies.platform(bom.get()))
      }
      .get()

  configurations.configureEach {
    val configuration = this
    if (configuration.name == CONTRACTS_PLATFORM_CONFIGURATION) return@configureEach
    var aligned = false
    dependencies.withType(ExternalModuleDependency::class.java).configureEach {
      if (!aligned && "$group:$name" in Contracts.modules) {
        aligned = true
        configuration.extendsFrom(platformBucket)
      }
    }
  }
}

private const val CONTRACTS_PLATFORM_CONFIGURATION = "composeAiContractsPlatform"
