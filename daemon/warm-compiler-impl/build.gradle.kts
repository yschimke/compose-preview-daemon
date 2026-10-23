// The warm in-process Kotlin compiler behind `composeai.daemon.warmCompile` — see
// docs/daemon/WARM-COMPILE.md.
//
// Compiled against kotlin-compiler-embeddable's *internal* pipeline (the frontend phase, FIR
// session factories, the plugin registrar seam), which carry no compatibility promise. This module
// builds one jar per Kotlin minor line (2.4, 2.5) from shared sources (`src/main`) plus a
// line-specific pipeline (`src/k24`, `src/k25`), and tests each jar against every released compiler
// of its line. `:daemon:core` embeds them as resources and loads the one for the line of the
// compiler the launch descriptor supplies, next to those Build Tools API impl jars, behind a facade
// that speaks only JDK types. A line with no jar keeps the daemon on the Build Tools API.
//
// NOT published to Maven: the jars ship inside `daemon-core`'s jar.

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

/**
 * One Kotlin minor line this module builds a jar for: compiled against [version], and its tests
 * also run against each of [alsoTested], the line's other released compilers it supports, oldest
 * first. The jar serves its line from the oldest of those up: an older compiler of the line is
 * refused at load (2.4.0 and 2.4.10 lack the 2.4.20 frontend API `src/k24` calls), and a newer one,
 * released after the jar, is used untested; the daemon falls back to the Build Tools API if the jar
 * fails to link against it.
 */
class CompilerLine(
  val name: String,
  val version: String,
  val alsoTested: List<String>,
  /** Compilers of the line this jar must refuse at load, tested as such. */
  val refused: List<String> = emptyList(),
) {
  val minor: String = version.split('.').take(2).joinToString(".")
  val supportedFrom: String = alsoTested.firstOrNull() ?: version
}

val lines =
  listOf(
    CompilerLine("k24", libs.versions.kotlin.get(), listOf("2.4.20"), refused = listOf("2.4.10")),
    CompilerLine("k25", libs.versions.warmCompilerKotlinNext.get(), emptyList()),
  )

sourceSets {
  main { kotlin.srcDir("src/k24/kotlin") }
  create("k25") { kotlin.srcDir("src/main/kotlin") }
}

kotlin {
  compilerOptions {
    optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    optIn.add("org.jetbrains.kotlin.cli.pipeline.PipelineArtifact.CliPipelineInternals")
  }
}

// A pre-release compiler's classes are rejected by a stable compiler unless told otherwise; the
// version check at load is what keeps them apart at run time.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileK25Kotlin") {
  compilerOptions.freeCompilerArgs.add("-Xskip-prerelease-check")
}

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${lines[0].version}")
  "k25CompileOnly"("org.jetbrains.kotlin:kotlin-compiler-embeddable:${lines[1].version}")
  testImplementation(libs.junit)
}

// The compiler version each jar links against and the oldest of its line it supports, checked at
// load against the one on the class path.
for (line in lines) {
  val sourceSet = if (line.name == "k24") "main" else line.name
  val out = layout.buildDirectory.dir("generated/built-against/${line.name}")
  val version = line.version
  val supportedFrom = line.supportedFrom
  val task =
    tasks.register("writeBuiltAgainst${line.name.replaceFirstChar { it.uppercase() }}") {
      inputs.property("kotlin", version)
      inputs.property("supportedFrom", supportedFrom)
      outputs.dir(out)
      doLast {
        val dir = out.get().dir("ee/schimke/composeai/daemon/warm/impl").asFile
        dir.mkdirs()
        File(dir, "built-against.txt").writeText(version)
        File(dir, "supported-from.txt").writeText(supportedFrom)
      }
    }
  sourceSets[sourceSet].resources.srcDir(task)
}

val k25Jar =
  tasks.register<Jar>("k25Jar") {
    archiveClassifier.set("k25")
    from(sourceSets["k25"].output)
  }

// What `:daemon:core` bundles: one jar per compiler line, named for its Kotlin minor version.
val warmCompilerJars =
  configurations.create("warmCompilerJars") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("warm-compiler-jars")) }
  }

val bundleJars =
  tasks.register<Sync>("bundleJars") {
    val k24Name = "warm-compiler-impl-${lines[0].minor}.jar"
    val k25Name = "warm-compiler-impl-${lines[1].minor}.jar"
    from(tasks.named<Jar>("jar")) { rename { k24Name } }
    from(k25Jar) { rename { k25Name } }
    into(layout.buildDirectory.dir("warm-compiler-jars"))
  }

artifacts {
  add(warmCompilerJars.name, bundleJars.map { it.destinationDir }) { builtBy(bundleJars) }
}

// The tests load each jar the way the daemon does: with its compiler, the Compose plugin and the
// Compose runtime resolved as plain files, never on the test class path.
val testComposeRuntime =
  configurations.create("testComposeRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
      )
    }
  }

dependencies { testComposeRuntime(libs.jetbrains.compose.runtime) }

for (line in lines) {
  val jar = if (line.name == "k24") tasks.named<Jar>("jar") else k25Jar
  for (version in listOf(line.version) + line.alsoTested + line.refused) {
    val refused = version in line.refused
    val suffix =
      if (version == line.version) line.name else "${line.name}OnKotlin${version.replace('.', '_')}"
    val compiler =
      configurations.create("${suffix}TestCompiler") {
        isCanBeConsumed = false
        isCanBeResolved = true
      }
    val plugin =
      configurations.create("${suffix}TestComposePlugin") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
      }
    dependencies {
      compiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$version")
      plugin("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$version")
    }
    val configure: Test.() -> Unit = {
      val implJar = jar.flatMap { it.archiveFile }
      val compilerFiles: FileCollection = files(compiler)
      val pluginFiles: FileCollection = files(plugin)
      val runtimeFiles: FileCollection = files(testComposeRuntime)
      inputs.file(implJar)
      inputs.files(compilerFiles, pluginFiles, runtimeFiles)
      jvmArgumentProviders.add(
        CommandLineArgumentProvider {
          listOf(
            "-Dwarm.test.implJar=${implJar.get().asFile.absolutePath}",
            "-Dwarm.test.compiler=${compilerFiles.asPath}",
            "-Dwarm.test.composePlugin=${pluginFiles.asPath}",
            "-Dwarm.test.composeRuntime=${runtimeFiles.asPath}",
            "-Dwarm.test.expectRefused=$refused",
          )
        }
      )
      maxHeapSize = "2g"
    }
    if (suffix == "k24") {
      tasks.named<Test>("test") { configure() }
    } else {
      val test = tasks.named<Test>("test")
      val task =
        tasks.register<Test>("test${suffix.replaceFirstChar { it.uppercase() }}") {
          description =
            if (refused) "Checks the ${line.minor} warm compiler refuses Kotlin $version."
            else "Runs the ${line.minor} warm compiler tests against Kotlin $version."
          group = "verification"
          testClassesDirs = test.get().testClassesDirs
          classpath = test.get().classpath
          configure()
        }
      tasks.named("check") { dependsOn(task) }
    }
  }
}
