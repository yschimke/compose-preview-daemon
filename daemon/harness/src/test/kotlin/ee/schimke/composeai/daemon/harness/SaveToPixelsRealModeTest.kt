package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.CompileResultKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * **Save to pixels** — times what an editor save costs end to end on the real desktop daemon: the
 * edited file goes through `compileSources`, then `renderNow`, until `renderFinished` names the new
 * PNG. Both in-process compilers run over the same generated Compose module, each against a fresh
 * copy of its classes: the Build Tools API's incremental compile (`bta`) and the warm compiler in
 * front of it (`warm`, docs/daemon/WARM-COMPILE.md).
 *
 * Every iteration is checked in pixels, not just timed: a leaf edit must paint the new background,
 * and a shared-component edit must render differently from the previous edit and identically to the
 * one before it (the edits alternate between two variants).
 *
 * Desktop real mode only:
 * ```
 * ./gradlew :daemon:harness:test -Pharness.host=real --tests '*SaveToPixels*'
 * ```
 *
 * Knobs: `-Pharness.saveLoop.modes=bta,warm`, `-Pharness.saveLoop.iterations=15`,
 * `-Pharness.saveLoop.warmup=5`, `-Pharness.saveLoop.extraJvmArgs=-Dkey=value|-Dkey2=value2`.
 * Results: `build/reports/daemon-harness/save-to-pixels.csv` and `save-to-pixels.txt`.
 */
class SaveToPixelsRealModeTest {

  @Test
  fun `a save reaches the pixels, through each in-process compiler`() {
    Assume.assumeTrue(
      "Skipping SaveToPixelsRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real" && HarnessTestSupport.harnessTarget() == "desktop",
    )
    val btaImpl = pathsProperty("composeai.harness.btaImplClasspath")
    val composePlugin = pathsProperty("composeai.harness.composePlugin")
    Assume.assumeTrue(
      "BTA impl classpath not resolved",
      btaImpl.isNotEmpty() && composePlugin.isNotEmpty(),
    )

    val modes = (System.getProperty("composeai.harness.saveLoop.modes") ?: "bta,warm").split(',')
    val iterations = Integer.getInteger("composeai.harness.saveLoop.iterations", 15)
    val warmup = Integer.getInteger("composeai.harness.saveLoop.warmup", 5)
    val extraJvmArgs =
      System.getProperty("composeai.harness.saveLoop.extraJvmArgs").orEmpty().split('|').filter {
        it.isNotBlank()
      }

    val work =
      File("build/daemon-harness/save-to-pixels").absoluteFile.apply {
        deleteRecursively()
        mkdirs()
      }
    val module = BenchModule(File(work, "src"))
    // The daemon's own class path carries Compose Desktop; it doubles as the module's compile class
    // path, as a consumer's runtime class path does in production.
    val daemonClasspath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
    val compileClasspath = daemonClasspath.filter { it.exists() }
    val pristine = File(work, "pristine-classes")
    stockCompile(btaImpl, composePlugin, compileClasspath, module.allFiles(), pristine)

    val reportDir = File("build/reports/daemon-harness").apply { mkdirs() }
    val csv =
      StringBuilder(
        "mode,scenario,iteration,compile_rpc_ms,compile_server_ms,render_ms,took_ms,total_ms\n"
      )
    val summary = StringBuilder()
    val traces = StringBuilder()

    for (mode in modes) {
      module.reset()
      val classes = File(work, "$mode/classes")
      pristine.copyRecursively(classes, overwrite = true)
      val rendersDir = File(work, "$mode/renders").apply { mkdirs() }
      val manifest = File(work, "$mode/previews.json")
      manifest.writeText(
        """{"previews":[""" +
          SCENARIOS.joinToString(",") {
            """{"id":"${it.preview}","className":"bench.${it.preview}Kt","functionName":"${it.preview}",""" +
              """"widthPx":160,"heightPx":120,"density":1.0,"showBackground":true,"outputBaseName":"${it.preview}"}"""
          } +
          "]}"
      )
      val launcher =
        RealDesktopHarnessLauncher(
          rendersDir = rendersDir,
          previewsManifest = manifest,
          classpath = daemonClasspath,
          extraJvmArgs =
            listOf(
              "-Dcomposeai.daemon.userClassDirs=${classes.absolutePath}",
              "-Dcomposeai.daemon.bta.implClasspath=${btaImpl.joinToString(File.pathSeparator)}",
              "-Dcomposeai.daemon.bta.compileClasspath=${compileClasspath.joinToString(File.pathSeparator)}",
              "-Dcomposeai.daemon.bta.compilerPlugins=${composePlugin.joinToString(File.pathSeparator)}",
              "-Dcomposeai.daemon.bta.moduleName=bench",
              "-Dcomposeai.daemon.bta.outputDir=${classes.absolutePath}",
              "-Dcomposeai.daemon.bta.icWorkingDir=${File(work, "$mode/ic").absolutePath}",
              "-Dcomposeai.daemon.warmCompile=${mode == "warm"}",
            ) + extraJvmArgs,
        )
      val client = HarnessClient.start(launcher)
      try {
        assertEquals(2, client.initialize().protocolVersion)
        client.sendInitialized()
        // The Build Tools API needs its incremental caches seeded once, as a first save would.
        val seed =
          client.compileSources(
            module.allFiles().map { it.path },
            changes = null,
            timeout = 300.seconds,
          )
        assertEquals("seed compile: ${seed.errors}", CompileResultKind.OK, seed.result)

        for (scenario in SCENARIOS) {
          // The per-phase timings (set-up, frames, encode, write, tear-down) ride on
          // renderFinished.
          if (System.getProperty("composeai.harness.saveLoop.trace") == "true") {
            client.extensionsEnable(listOf("render/trace"))
            client.dataSubscribe(scenario.preview, "render/trace")
          }
          val pngs = ArrayList<ByteArray>()
          val totals = ArrayList<Long>()
          val compiles = ArrayList<Long>()
          val renders = ArrayList<Long>()
          for (i in 0 until warmup + iterations) {
            val edited = scenario.edit(module, i)
            val start = System.nanoTime()
            val compiled =
              client.compileSources(
                module.allFiles().map { it.path },
                SourceChangeSet(modified = listOf(edited.path), removed = emptyList()),
              )
            val compiledAt = System.nanoTime()
            assertEquals(
              "$mode ${scenario.name} $i: ${compiled.errors}",
              CompileResultKind.OK,
              compiled.result,
            )
            client.renderNow(listOf(scenario.preview), RenderTier.FAST)
            val finished = client.pollRenderFinishedFor(scenario.preview, timeout = 120.seconds)
            val end = System.nanoTime()
            val params = finished["params"]!!.jsonObject
            val png = File(params["pngPath"]!!.jsonPrimitive.contentOrNull!!).readBytes()
            scenario.check(png, i, pngs)
            pngs += png
            if (i < warmup) continue
            traces.append(
              "$mode ${scenario.name} $i ${params["workTrace"]} ${params["dataProducts"]}\n"
            )
            val compileMs = (compiledAt - start) / 1_000_000
            val renderMs = (end - compiledAt) / 1_000_000
            val totalMs = (end - start) / 1_000_000
            compiles += compileMs
            renders += renderMs
            totals += totalMs
            csv.append(
              "$mode,${scenario.name},$i,$compileMs,${compiled.durationMs},$renderMs," +
                "${params["tookMs"]?.jsonPrimitive?.longOrNull},$totalMs\n"
            )
          }
          summary.append(
            "%-5s %-10s save→pixels median %5d ms (p90 %5d) | compileSources %5d ms | render %5d ms\n"
              .format(
                mode,
                scenario.name,
                median(totals),
                p90(totals),
                median(compiles),
                median(renders),
              )
          )
        }
        assertEquals(0, client.shutdownAndExit(timeout = 30.seconds))
      } catch (t: Throwable) {
        System.err.println("SaveToPixels [$mode] failed; daemon stderr:\n${client.dumpStderr()}")
        throw t
      } finally {
        File(reportDir, "save-to-pixels-$mode-stderr.txt").writeText(client.dumpStderr())
        client.close()
      }
    }
    File(reportDir, "save-to-pixels.csv").writeText(csv.toString())
    File(reportDir, "save-to-pixels.txt").writeText(summary.toString())
    File(reportDir, "save-to-pixels-traces.txt").writeText(traces.toString())
    println(summary)
  }

  private class Scenario(
    val name: String,
    val preview: String,
    val edit: (BenchModule, Int) -> File,
    val check: (png: ByteArray, iteration: Int, previous: List<ByteArray>) -> Unit,
  )

  /** A Compose module shaped like an app's: screens built from shared components. */
  private class BenchModule(private val dir: File) {
    fun reset() {
      dir.deleteRecursively()
      dir.mkdirs()
      repeat(COMPONENTS) { component(it, 0) }
      repeat(SCREENS) { screen(it, null) }
    }

    init {
      reset()
    }

    fun allFiles(): List<File> =
      dir.listFiles()!!.filter { it.name.endsWith(".kt") }.sortedBy { it.name }

    fun component(k: Int, variant: Int): File =
      File(dir, "Component$k.kt").apply {
        writeText(
          """
          package bench
          import androidx.compose.foundation.background
          import androidx.compose.foundation.layout.*
          import androidx.compose.foundation.text.BasicText
          import androidx.compose.runtime.Composable
          import androidx.compose.ui.Modifier
          import androidx.compose.ui.graphics.Color
          import androidx.compose.ui.unit.dp
          @Composable fun Component$k(label: String) {
            Row(Modifier.fillMaxWidth().height(24.dp).background(Color(${COLORS[variant % 2]}))) {
              BasicText("C$k ${'$'}label v${variant % 2}")
            }
          }
          """
            .trimIndent()
        )
      }

    fun screen(n: Int, color: Long?): File =
      File(dir, "Screen$n.kt").apply {
        writeText(
          """
          package bench
          import androidx.compose.foundation.background
          import androidx.compose.foundation.layout.*
          import androidx.compose.foundation.text.BasicText
          import androidx.compose.runtime.Composable
          import androidx.compose.ui.Modifier
          import androidx.compose.ui.graphics.Color
          import androidx.compose.ui.unit.dp
          @Composable fun Screen$n() {
            Box(Modifier.fillMaxSize().background(Color(0x${"%08X".format(color ?: 0xFFEEEEEE)}))) {
              Column(Modifier.padding(top = 40.dp)) {
                Component${n % COMPONENTS}("screen $n")
                BasicText("Screen $n")
              }
            }
          }
          """
            .trimIndent()
        )
      }
  }

  private fun stockCompile(
    compiler: List<File>,
    plugin: List<File>,
    classpath: List<File>,
    sources: List<File>,
    out: File,
  ) {
    URLClassLoader(
        compiler.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
      .use { loader ->
        val k2 =
          loader
            .loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            .getConstructor()
            .newInstance()
        val log = ByteArrayOutputStream()
        val args =
          listOf(
            "-classpath",
            classpath.joinToString(File.pathSeparator),
            "-d",
            out.path,
            "-jvm-target",
            "17",
            "-module-name",
            "bench",
            "-no-stdlib",
            "-no-reflect",
            "-Xplugin=${plugin.joinToString(",")}",
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true",
          ) + sources.map { it.path }
        val code =
          k2.javaClass
            .getMethod("exec", PrintStream::class.java, Array<String>::class.java)
            .invoke(k2, PrintStream(log), args.toTypedArray())
        assertEquals(log.toString(), "OK", code.toString())
      }
  }

  private companion object {
    const val SCREENS = 50
    const val COMPONENTS = 10
    val COLORS = listOf("0xFFB3E5FC", "0xFFFFCCBC")

    /** Leaf colours: distinct per iteration, so a stale class can never repaint the same pixel. */
    fun leafColor(i: Int): Long = 0xFF000000 or ((i * 0x2F1B3DL + 0x405060L) and 0xFFFFFF)

    val SCENARIOS =
      listOf(
        Scenario(
          "leaf",
          preview = "Screen7",
          edit = { module, i -> module.screen(7, leafColor(i)) },
          check = { png, i, _ ->
            val corner = ImageIO.read(png.inputStream()).getRGB(2, 2)
            assertEquals("leaf $i painted a stale background", leafColor(i).toInt(), corner)
          },
        ),
        Scenario(
          "component",
          preview = "Screen3",
          edit = { module, i -> module.component(3, i + 1) },
          check = { png, i, previous ->
            if (i >= 1)
              assertFalse(
                "component $i rendered the previous edit",
                png.contentEquals(previous[i - 1]),
              )
            if (i >= 2)
              assertTrue(
                "component $i differs from the same variant two edits ago",
                png.contentEquals(previous[i - 2]),
              )
          },
        ),
      )

    fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    fun p90(values: List<Long>): Long =
      values.sorted()[(values.size * 9 / 10).coerceAtMost(values.size - 1)]

    fun pathsProperty(key: String): List<File> =
      System.getProperty(key)
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
  }
}
