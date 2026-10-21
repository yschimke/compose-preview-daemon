// The warm in-process Kotlin compiler behind `composeai.daemon.warmCompile` — see
// docs/daemon/WARM-COMPILE.md.
//
// Compiled against kotlin-compiler-embeddable's *internal* pipeline (the frontend phase, FIR
// session factories, the plugin registrar seam), so each jar only links against the exact compiler
// version it was built with. This module builds one jar per supported compiler line from shared
// sources (`src/main`) plus a version-specific pipeline (`src/k24`, `src/k25`). `:daemon:core`
// embeds them as resources and loads the one matching the compiler the launch descriptor supplies,
// next to those Build Tools API impl jars, behind a facade that speaks only JDK types. Any other
// version keeps the daemon on the Build Tools API.
//
// NOT published to Maven: the jars ship inside `daemon-core`'s jar.

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

/** One compiler line this module builds a jar for. */
class CompilerLine(val name: String, val version: String)

val lines =
  listOf(
    CompilerLine("k24", libs.versions.kotlin.get()),
    CompilerLine("k25", libs.versions.warmCompilerKotlinNext.get()),
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

// The compiler version each jar links against, checked at load against the one on the class path.
for (line in lines) {
  val sourceSet = if (line.name == "k24") "main" else line.name
  val out = layout.buildDirectory.dir("generated/built-against/${line.name}")
  val version = line.version
  val task =
    tasks.register("writeBuiltAgainst${line.name.replaceFirstChar { it.uppercase() }}") {
      inputs.property("kotlin", version)
      outputs.dir(out)
      doLast {
        val file = out.get().file("ee/schimke/composeai/daemon/warm/impl/built-against.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(version)
      }
    }
  sourceSets[sourceSet].resources.srcDir(task)
}

val k25Jar =
  tasks.register<Jar>("k25Jar") {
    archiveClassifier.set("k25")
    from(sourceSets["k25"].output)
  }

// What `:daemon:core` bundles: one jar per compiler line, named for its compiler version.
val warmCompilerJars =
  configurations.create("warmCompilerJars") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("warm-compiler-jars")) }
  }

val bundleJars =
  tasks.register<Sync>("bundleJars") {
    val k24Name = "warm-compiler-impl-${lines[0].version}.jar"
    val k25Name = "warm-compiler-impl-${lines[1].version}.jar"
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
  val compiler =
    configurations.create("${line.name}TestCompiler") {
      isCanBeConsumed = false
      isCanBeResolved = true
    }
  val plugin =
    configurations.create("${line.name}TestComposePlugin") {
      isCanBeConsumed = false
      isCanBeResolved = true
      isTransitive = false
    }
  dependencies {
    compiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:${line.version}")
    plugin("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${line.version}")
  }
  val jar = if (line.name == "k24") tasks.named<Jar>("jar") else k25Jar
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
        )
      }
    )
    maxHeapSize = "2g"
  }
  if (line.name == "k24") {
    tasks.named<Test>("test") { configure() }
  } else {
    val test = tasks.named<Test>("test")
    val testK25 =
      tasks.register<Test>("test${line.name.replaceFirstChar { it.uppercase() }}") {
        description = "Runs the warm compiler tests against Kotlin ${line.version}."
        group = "verification"
        testClassesDirs = test.get().testClassesDirs
        classpath = test.get().classpath
        configure()
      }
    tasks.named("check") { dependsOn(testK25) }
  }
}
