package ee.schimke.composeai.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * Pins a published module's public ABI in a committed dump under `api/`.
 *
 * `checkKotlinAbi` diffs the real public ABI against that dump, so a change to the published
 * surface is a diff in review rather than something a consumer in another repository discovers when
 * it bumps. Regenerate deliberately with `./gradlew :<module>:updateKotlinAbi` — or repo-wide with
 * `./gradlew updateKotlinAbi` — and commit the result.
 *
 * Applied by id from a module's `plugins {}` block rather than folded into
 * `composeai.maven-publishing`, because it is **not** applicable to every published module: see
 * [wireAndroid] for the Android modules this deliberately refuses.
 *
 * This says nothing about `explicitApi()`. The two are independent — a dump records whatever is
 * public, explicit or implicit — and the modules that pair them do so for their own reasons. Adding
 * the gate does not require an explicit-API pass first.
 */
class ComposeAiAbiValidationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
      project.extensions.configure<KotlinJvmProjectExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation { project.configureDump(this) }
      }
      project.wireCheck()
    }
    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      project.extensions.configure<KotlinMultiplatformExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation { project.configureDump(this) }
      }
      project.wireCheck()
    }
    // Both AGP ids, because the refusal below keys on the Android build, not on a Kotlin plugin:
    // these modules apply no KGP Android plugin to watch for.
    project.pluginManager.withPlugin("com.android.library") { project.refuseAndroid() }
    project.pluginManager.withPlugin("com.android.application") { project.refuseAndroid() }
  }
}

/**
 * `api/`, explicitly, on both shapes.
 *
 * The JVM shape defaults to it and the multiplatform one is configured the same way, so the two
 * kinds of module put their dumps in the same place and a reviewer does not have to know which
 * shape a module is to find its recorded surface.
 */
@OptIn(ExperimentalAbiValidation::class)
private fun Project.configureDump(spec: AbiValidationExtension) {
  spec.referenceDumpDir.set(layout.projectDirectory.dir("api"))
  // Keep a target this host cannot build (a KMP module's wasmJs on a machine without the toolchain)
  // in the dump rather than silently dropping it, so a partial local `updateKotlinAbi` cannot
  // delete another platform's recorded surface. A no-op on a single-target JVM module.
  spec.keepLocallyUnsupportedTargets.set(true)
}

/**
 * `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
 * change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
 */
private fun Project.wireCheck() {
  tasks.named("check") { dependsOn("checkKotlinAbi") }
}

/**
 * Android is refused rather than wired, because it does not work and does not say so.
 *
 * The 23 Android modules here compile Kotlin through **AGP's built-in Kotlin support**, not the
 * Kotlin Android Gradle plugin — dumping their applied plugins shows `LibraryPlugin` and
 * `KotlinBaseApiPlugin` and no `org.jetbrains.kotlin.android` at all. KGP's ABI validation has
 * nothing to hook onto there, and it fails in the worst possible way:
 *
 * `abiValidation()` on such a module leaves the update task's output property with no value
 * (`Cannot query the value of this provider because it has no value available`). Giving it one — an
 * explicit [AbiValidationExtension.referenceDumpDir] — makes the task *succeed* and write
 * **nothing**: an empty `api/` directory for a module with plenty of public Kotlin, under every
 * `binariesSource` (`MAIN_COMPILATION`, `NON_TEST_COMPILATIONS`, `MAVEN_PUBLICATIONS` all measured).
 *
 * A committed empty dump makes `checkKotlinAbi` pass vacuously, so those modules would read as
 * gated while recording no surface and catching no break. Failing loudly keeps the coverage honest:
 * they are ungated, and stay ungated until Kotlin can dump an AGP-built-in-Kotlin module.
 */
private fun Project.refuseAndroid(): Nothing =
  error(
    "composeai.abi-validation does not support Android modules: they build Kotlin through AGP's " +
      "built-in support rather than the Kotlin Android plugin, and Kotlin 2.4.20 writes an empty " +
      "dump for them, which would make checkKotlinAbi pass vacuously. Remove the plugin from " +
      "$path and leave the module ungated until Kotlin can dump one."
  )
