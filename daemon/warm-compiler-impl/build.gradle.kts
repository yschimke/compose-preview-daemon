// The warm in-process Kotlin compiler behind `composeai.daemon.warmCompile` — see
// docs/daemon/WARM-COMPILE.md.
//
// Compiled against kotlin-compiler-embeddable's *internal* pipeline (the frontend phase, FIR
// session factories, the plugin registrar seam), so it only links against the exact compiler
// version it was built with. It never shares a class loader with the daemon: `:daemon:core`
// embeds this module's jar as a resource and loads it next to the Build Tools API impl jars the
// launch descriptor already supplies (which carry kotlin-compiler-embeddable), behind a facade
// that speaks only JDK types. A version mismatch is detected at load and the daemon keeps using
// the Build Tools API.
//
// NOT published to Maven: it ships inside `daemon-core`'s jar.

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

val kotlinVersion = libs.versions.kotlin.get()

// The compiler, the Compose plugin, and the Compose runtime the tests compile against. Resolved
// as plain files and handed to the tests, which load the compiler the way the daemon does: in a
// class loader of its own, never on the test class path.
val testCompiler =
  configurations.create("testCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }
val testComposePlugin =
  configurations.create("testComposePlugin") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }
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

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

  testImplementation(libs.junit)
  testCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
  testComposePlugin("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$kotlinVersion")
  testComposeRuntime(libs.jetbrains.compose.runtime)
}

kotlin {
  compilerOptions {
    optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    optIn.add("org.jetbrains.kotlin.cli.pipeline.PipelineArtifact.CliPipelineInternals")
  }
}

tasks.withType<Test>().configureEach {
  val implJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
  val compiler: FileCollection = files(testCompiler)
  val composePlugin: FileCollection = files(testComposePlugin)
  val composeRuntime: FileCollection = files(testComposeRuntime)
  inputs.file(implJar)
  inputs.files(compiler, composePlugin, composeRuntime)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dwarm.test.implJar=${implJar.get().asFile.absolutePath}",
        "-Dwarm.test.compiler=${compiler.asPath}",
        "-Dwarm.test.composePlugin=${composePlugin.asPath}",
        "-Dwarm.test.composeRuntime=${composeRuntime.asPath}",
      )
    }
  )
  maxHeapSize = "2g"
}

// The compiler version this jar links against, checked at load against the one on the class path.
val builtAgainst =
  tasks.register("writeBuiltAgainst") {
    val out = layout.buildDirectory.dir("generated/built-against")
    val version = kotlinVersion
    inputs.property("kotlin", version)
    outputs.dir(out)
    doLast {
      val file = out.get().file("ee/schimke/composeai/daemon/warm/impl/built-against.txt").asFile
      file.parentFile.mkdirs()
      file.writeText(version)
    }
  }

sourceSets.main.get().resources.srcDir(builtAgainst)
