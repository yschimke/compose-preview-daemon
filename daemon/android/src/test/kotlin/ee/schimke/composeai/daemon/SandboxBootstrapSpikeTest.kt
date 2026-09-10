package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.pool.SandboxProcessPool
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.notification.RunNotifier
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in B2 experiment. Each route gets a fresh JVM: native graphics cannot use two sandboxes. */
class SandboxBootstrapSpikeTest {
  @Test
  fun compareJUnitAndDirectExecution() {
    assumeTrue(System.getenv("COMPOSEAI_BOOT_SPIKE") == "true")
    for (trial in 0..2) {
      val root = File("build/boot-spike/trial-$trial").apply { mkdirs() }
      val modes = if (trial % 2 == 0) listOf("junit", "direct") else listOf("direct", "junit")
      for (mode in modes) {
        val output =
          root.resolve(mode).apply {
            deleteRecursively()
            mkdirs()
          }
        val command = SandboxProcessPool.spareWorkerCommandForTest().dropLast(1).toMutableList()
        if (System.getenv("COMPOSEAI_BOOT_SPIKE_CLASS_LOG") == "true") {
          command.add(
            1,
            "-Xlog:class+load=info:file=${output.resolve("classes.log").absolutePath}:uptimemillis",
          )
        }
        command += SandboxBootstrapSpikeMain::class.java.name
        command += listOf(mode, output.resolve("renders").absolutePath)
        val log = root.resolve("$mode.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        try {
          assertTrue("$mode timed out; see $log", process.waitFor(180, TimeUnit.SECONDS))
          assertEquals("$mode failed:\n${log.readText()}", 0, process.exitValue())
        } finally {
          if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
          }
        }
        log
          .readLines()
          .filter { it.startsWith("[measure]") }
          .forEach { println("[trial=$trial] $it") }
      }
      for (name in listOf("RedSquare", "MaterialButtonInteractionState")) {
        val baseline = root.resolve("junit/renders/$name.png")
        val candidate = root.resolve("direct/renders/$name.png")
        assertTrue("missing $baseline", baseline.isFile)
        assertTrue("missing $candidate", candidate.isFile)
        assertArrayEquals("PNG parity for $name", baseline.readBytes(), candidate.readBytes())
        val hierarchy = "data/$name/uia-hierarchy.json"
        assertEquals(
          "hierarchy parity for $name",
          root.resolve("junit/$hierarchy").readText(),
          root.resolve("direct/$hierarchy").readText(),
        )
        if (name == "MaterialButtonInteractionState") {
          assertTrue(
            "button semantics must survive",
            root.resolve("direct/$hierarchy").readText().contains("\"role\":\"Button\""),
          )
        }
        val image = ImageIO.read(candidate)
        assertEquals(320, image.width)
        assertEquals(320, image.height)
        if (name == "RedSquare") {
          assertEquals("red fixture must paint pixels", 0xffef5350.toInt(), image.getRGB(160, 160))
        }
      }
    }
  }
}

/** Test-only main; no experimental switch is exposed on the published daemon. */
object SandboxBootstrapSpikeMain {
  @JvmStatic
  fun main(args: Array<String>) {
    try {
      require(args.size == 2 && args[0] in listOf("junit", "direct"))
      System.setProperty(RenderEngine.OUTPUT_DIR_PROP, args[1])
      System.setProperty("roborazzi.test.record", "true")
      println("[measure] mode=${args[0]} java=${System.getProperty("java.version")}")
      if (args[0] == "junit") {
        val result = JUnitCore.runClasses(SandboxBootstrapFixture::class.java)
        result.failures.forEach { it.exception.printStackTrace() }
        check(result.wasSuccessful()) { "JUnit bootstrap failed" }
      } else {
        DirectBootstrap().render()
      }
    } catch (t: Throwable) {
      t.printStackTrace()
      exitProcess(1)
    }
    // Robolectric owns persistent executor threads. All lifecycle cleanup has finished above.
    exitProcess(0)
  }

  /**
   * Reuse the daemon runner as a configuration adapter, but bypass JUnit's execution, helper runner
   * and statement lifecycle. This is deliberately not a JUnit-free dependency graph. Keep
   * application setup: the simulator also needs it, as does RenderEngine's ActivityScenario.
   */
  private class DirectBootstrap : SandboxHoldingRunner(SandboxBootstrapFixture::class.java) {
    fun render() {
      val method = children.single()
      val sandbox = getSandbox(method)
      // Shadow providers must be resolved before entering the sandbox classloader.
      configureSandbox(sandbox, method)
      sandbox.runOnMainThreadWithClassLoader(
        Runnable {
          val fixture = sandbox.bootstrappedClass<Any>(SandboxBootstrapFixture::class.java)
          val bootstrapped = fixture.getMethod("renderFixtures")
          var failure: Throwable? = null
          try {
            beforeTest(sandbox, method, bootstrapped)
            bootstrapped.invoke(fixture.getDeclaredConstructor().newInstance())
          } catch (t: Throwable) {
            failure = t
          }
          // Attempt both cleanup phases even if initialization or rendering failed, matching the
          // runner's failure path. Preserve the first failure rather than masking it with cleanup.
          for (cleanup in
            listOf({ afterTest(method, bootstrapped) }, { finallyAfterTest(method) })) {
            try {
              cleanup()
            } catch (t: Throwable) {
              if (failure == null) failure = t else failure.addSuppressed(t)
            }
          }
          failure?.let { throw it }
        }
      )
    }
  }

  // Gradle also discovers public nested fixtures. Gate before super.run so a normal suite
  // neither boots a sandbox nor writes render artifacts for this experiment.
  class SpikeRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    override fun run(notifier: RunNotifier) {
      if (System.getenv("COMPOSEAI_BOOT_SPIKE") == "true") {
        super.run(notifier)
      } else {
        notifier.fireTestIgnored(description)
      }
    }
  }

  @RunWith(SpikeRunner::class)
  @Config(sdk = [35])
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  class SandboxBootstrapFixture {
    @Test
    fun renderFixtures() {
      val runtime = ManagementFactory.getRuntimeMXBean()
      println("[measure] sandboxReadyMs=${runtime.uptime}")
      val engine = RenderEngine()
      for ((index, name) in listOf("RedSquare", "MaterialButtonInteractionState").withIndex()) {
        val start = System.nanoTime()
        engine.render(
          RenderSpec(
            className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            functionName = name,
            widthPx = 320,
            heightPx = 320,
            outputBaseName = name,
          ),
          requestId = index.toLong(),
        )
        println(
          "[measure] fixture=$name renderMs=${(System.nanoTime() - start) / 1_000_000} processUptimeMs=${runtime.uptime}"
        )
      }
    }
  }
}
