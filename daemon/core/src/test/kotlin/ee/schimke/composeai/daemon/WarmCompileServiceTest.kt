package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import ee.schimke.composeai.daemon.config.DaemonProperties.Names as DaemonPropertiesNames
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing between the warm compiler and its Build Tools API fallback. */
class WarmCompileServiceTest {

  private class FakeFallback : BtaCompileService {
    val calls = mutableListOf<SourceChangeSet?>()
    var outcome: BtaCompileService.Outcome = BtaCompileService.Outcome.Ok

    override fun compile(
      sources: List<Path>,
      changes: SourceChangeSet?,
    ): BtaCompileService.Outcome {
      calls += changes
      return outcome
    }
  }

  private class FakeWarm(var next: Map<String, Any?> = mapOf("status" to "ok")) :
    WarmCompileService.WarmCompiler {
    val compiled = mutableListOf<List<String>>()
    var invalidations = 0

    override fun compile(sources: List<String>): Map<String, Any?> {
      compiled += sources
      return next
    }

    override fun invalidate() {
      invalidations++
    }
  }

  private val sources = listOf(Path.of("/m/A.kt"), Path.of("/m/B.kt"))

  private fun service(fallback: FakeFallback, warm: () -> WarmCompileService.WarmCompiler) =
    WarmCompileService(fallback, warm, log = {})

  @Test
  fun `the warm compiler is on by default and the property turns it off`() {
    val fallback = FakeFallback()
    val configured =
      mapOf(
        DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH to "/bta/impl.jar",
        DefaultBtaCompileService.SYSPROP_OUTPUT_DIR to "/m/classes",
        DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR to "/m/ic",
        DefaultBtaCompileService.SYSPROP_MODULE_NAME to "m",
      )
    val name = DaemonPropertiesNames.WARM_COMPILE
    val previous = System.getProperty(name)
    try {
      System.clearProperty(name)
      assertTrue(WarmCompileService.wrapIfEnabled(fallback, configured::get) is WarmCompileService)
      System.setProperty(name, "false")
      assertSame(fallback, WarmCompileService.wrapIfEnabled(fallback, configured::get))
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
  }

  @Test
  fun `a local edit compiles warm and never reaches the fallback`() {
    val fallback = FakeFallback()
    val warm = FakeWarm()
    val outcome =
      service(fallback) { warm }.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    assertEquals(BtaCompileService.Outcome.Ok, outcome)
    assertEquals(listOf(listOf("/m/A.kt")), warm.compiled)
    assertTrue(fallback.calls.isEmpty())
  }

  @Test
  fun `an unsupported edit falls back with every file compiled warm since`() {
    val fallback = FakeFallback()
    val warm = FakeWarm()
    val service = service(fallback) { warm }
    service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    warm.next = mapOf("status" to "unsupported", "reason" to "ABI changed")
    service.compile(sources, SourceChangeSet(modified = listOf("/m/B.kt")))

    assertEquals(listOf(SourceChangeSet(modified = listOf("/m/A.kt", "/m/B.kt"))), fallback.calls)
    assertEquals(1, warm.invalidations)

    // Once the fallback caught up, the next fallback carries only its own change.
    service.compile(sources, null)
    assertEquals(null, fallback.calls.last())
  }

  @Test
  fun `compile errors come back without a fallback`() {
    val fallback = FakeFallback()
    val warm =
      FakeWarm(
        mapOf(
          "status" to "errors",
          "errors" to
            listOf(
              mapOf(
                "file" to "/m/A.kt",
                "line" to 3,
                "column" to 5,
                "message" to "Unresolved reference",
              )
            ),
        )
      )
    val outcome =
      service(fallback) { warm }.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    outcome as BtaCompileService.Outcome.CompileError
    assertEquals(3, outcome.errors.single().line)
    assertTrue(fallback.calls.isEmpty())
  }

  @Test
  fun `removed files, unknown change sets and non-Kotlin sources go straight to the fallback`() {
    val fallback = FakeFallback()
    val warm = FakeWarm()
    val service = service(fallback) { warm }
    service.compile(sources, null)
    service.compile(sources, SourceChangeSet(modified = emptyList(), removed = listOf("/m/B.kt")))
    service.compile(sources, SourceChangeSet(modified = listOf("/m/J.java")))
    assertEquals(3, fallback.calls.size)
    assertTrue(warm.compiled.isEmpty())
  }

  @Test
  fun `a warm compiler that cannot load is never tried again`() {
    val fallback = FakeFallback()
    var attempts = 0
    val service =
      service(fallback) {
        attempts++
        error("built for Kotlin 2.4 but the compiler on the class path is 2.3.0")
      }
    repeat(3) { service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt"))) }
    assertEquals(1, attempts)
    assertEquals(3, fallback.calls.size)
  }

  @Test
  fun `a warm compiler that throws mid-compile falls back and is reset`() {
    val fallback = FakeFallback()
    val warm =
      object : WarmCompileService.WarmCompiler {
        var invalidated = false

        override fun compile(sources: List<String>): Map<String, Any?> =
          throw IllegalStateException("boom")

        override fun invalidate() {
          invalidated = true
        }
      }
    val outcome =
      service(fallback) { warm }.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    assertEquals(BtaCompileService.Outcome.Ok, outcome)
    assertEquals(1, fallback.calls.size)
    assertTrue(warm.invalidated)
  }

  @Test
  fun `a warm compiler that fails to link mid-compile is never tried again`() {
    val fallback = FakeFallback()
    var compiles = 0
    val warm =
      object : WarmCompileService.WarmCompiler {
        override fun compile(sources: List<String>): Map<String, Any?> {
          compiles++
          throw NoSuchMethodError("FirJvmSessionFactory.createSourceSession")
        }

        override fun invalidate() {}
      }
    val service = service(fallback) { warm }
    repeat(3) { service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt"))) }
    assertEquals(1, compiles)
    assertEquals(3, fallback.calls.size)
  }

  @Test
  fun `every compiler of a Kotlin minor line shares one bundled jar`() {
    assertEquals(
      "warm/warm-compiler-impl-2.4.jar",
      WarmCompileService.implJarResource("2.4.0"),
    )
    assertEquals(
      "warm/warm-compiler-impl-2.4.jar",
      WarmCompileService.implJarResource("2.4.21"),
    )
    assertEquals(
      "warm/warm-compiler-impl-2.5.jar",
      WarmCompileService.implJarResource("2.5.0-Beta2"),
    )
  }

  @Test
  fun `a link error the compiler reports as an internal error is never tried again`() {
    for (error in
      listOf(
        "NoSuchMethodError: 'void Foo.bar()'",
        "VerifyError: Bad type",
        "IllegalAccessError: x",
      )) {
      val fallback = FakeFallback()
      val warm =
        FakeWarm(
          mapOf(
            "status" to "failed",
            "reason" to "compiler exited INTERNAL_ERROR: java.lang.$error",
          )
        )
      val service = service(fallback) { warm }
      repeat(3) { service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt"))) }
      assertEquals(error, 1, warm.compiled.size)
      assertEquals(error, 3, fallback.calls.size)
    }
  }

  @Test
  fun `any other internal error is retried on the next edit`() {
    val fallback = FakeFallback()
    val warm =
      FakeWarm(
        mapOf(
          "status" to "failed",
          "reason" to "compiler exited INTERNAL_ERROR: java.lang.IllegalStateException: boom",
        )
      )
    val service = service(fallback) { warm }
    repeat(2) { service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt"))) }
    assertEquals(2, warm.compiled.size)
  }
}
