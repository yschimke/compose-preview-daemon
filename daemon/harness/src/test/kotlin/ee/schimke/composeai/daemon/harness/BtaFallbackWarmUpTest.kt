package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.WarmCompileService
import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * `compileWarmUp` on a module no warm compiler serves warms the real Build Tools API fallback
 * (docs/daemon/WARM-COMPILE.md § Warming up): with the impl the Gradle plugin would resolve, it
 * snapshots the class path into the module's cache, writes nothing the module's compiles read, and
 * the first edit after it compiles through the same, already loaded, session.
 */
class BtaFallbackWarmUpTest {

  @Test
  fun `a fallback warm-up snapshots the class path, installs nothing, and the first edit compiles`() {
    val btaImpl =
      System.getProperty("composeai.harness.btaImplClasspath")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
    Assume.assumeTrue("BTA impl classpath not resolved", btaImpl.isNotEmpty())
    val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())

    val root = Files.createTempDirectory("bta-fallback-warm-up").toFile()
    val classes = File(root, "classes")
    val ic = File(root, "ic")
    val source =
      File(root, "src/m/A.kt").apply {
        parentFile.mkdirs()
        writeText("package m\n\nfun a(): Int = 1\n")
      }
    val sysprops =
      mapOf(
        DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH to btaImpl.joinToString(File.pathSeparator),
        DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH to stdlib.path,
        DefaultBtaCompileService.SYSPROP_OUTPUT_DIR to classes.path,
        DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR to ic.path,
        DefaultBtaCompileService.SYSPROP_MODULE_NAME to "m",
      )
    val name = DaemonProperties.Names.WARM_COMPILE
    val previous = System.getProperty(name)
    try {
      // The warm compiler off: every edit goes to the Build Tools API, as on a line with no jar.
      System.setProperty(name, "false")
      val service = WarmCompileService.fromSysprops(sysprops::get) as WarmCompileService

      val warmUpStart = System.nanoTime()
      val outcome = service.warmUp()
      val warmUpMs = (System.nanoTime() - warmUpStart) / 1_000_000
      assertTrue(outcome.toString(), outcome is WarmCompileService.WarmUp.FallbackReady)

      assertEquals(
        "one snapshot per class-path entry, in the module's cache",
        1,
        File(ic, "cp-snapshots").listFiles { f -> f.name.endsWith(".bin") }!!.size,
      )
      assertFalse("the scratch directory is removed", File(ic, "warm-up-bta").exists())
      assertFalse("the module's IC caches are untouched", File(ic, "ic").exists())
      assertTrue(
        "nothing is installed",
        classes.walkTopDown().none { it.isFile && it.name.endsWith(".class") },
      )

      val compileStart = System.nanoTime()
      val compiled =
        service.compile(listOf(source.toPath()), SourceChangeSet(modified = listOf(source.path)))
      val compileMs = (System.nanoTime() - compileStart) / 1_000_000
      assertEquals(BtaCompileService.Outcome.Ok, compiled)
      assertTrue(File(classes, "m/AKt.class").isFile)
      println("bta fallback warm-up: $warmUpMs ms; first edit after it: $compileMs ms")
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
      root.deleteRecursively()
    }
  }
}
