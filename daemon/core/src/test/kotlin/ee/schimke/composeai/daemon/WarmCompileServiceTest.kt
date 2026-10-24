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
    val warmedUp = mutableListOf<List<String>>()
    var warmUpResult: Map<String, Any?> = mapOf("status" to "ok")
    var invalidations = 0

    override fun compile(sources: List<String>): Map<String, Any?> {
      compiled += sources
      return next
    }

    override fun warmUp(sources: List<String>): Map<String, Any?> {
      warmedUp += sources
      return warmUpResult
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
  fun `a warm-up loads the compiler once, from files of its own, for the edits after it`() {
    val fallback = FakeFallback()
    val warm = FakeWarm()
    var loads = 0
    val service =
      WarmCompileService(
        fallback,
        {
          loads++
          warm
        },
        log = {},
        warmUpSources = { listOf("/warm/WarmUp.kt") },
        warmUpMaxPasses = 1,
      )
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.Ready)
    assertEquals(listOf(listOf("/warm/WarmUp.kt")), warm.warmedUp)

    service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    assertEquals(1, loads)
    assertEquals(listOf(listOf("/m/A.kt")), warm.compiled)
    assertTrue(fallback.calls.isEmpty())
  }

  private fun warmUpService(
    fallback: FakeFallback,
    warm: WarmCompileService.WarmCompiler,
    maxPasses: Int,
  ) =
    WarmCompileService(
      fallback,
      { warm },
      log = {},
      warmUpSources = { listOf("/warm/WarmUp.kt") },
      warmUpMaxPasses = maxPasses,
      warmUpBudgetMs = 60_000,
    )

  private fun moduleFiles(vararg names: String): List<String> {
    val dir = java.nio.file.Files.createTempDirectory("warm-up-sources").toFile()
    return names.map { java.io.File(dir, it).apply { writeText("package m\n") }.path }
  }

  @Test
  fun `a warm-up cycles through the module's own files for its passes`() {
    val warm = FakeWarm()
    val (a, b) = moduleFiles("A.kt", "B.kt")
    val missing = java.io.File(a).resolveSibling("Gone.kt").path
    val notKotlin = moduleFiles("J.java").single()
    val outcome =
      warmUpService(FakeFallback(), warm, maxPasses = 5).warmUp(listOf(a, b, missing, notKotlin, a))
    outcome as WarmCompileService.WarmUp.Ready
    assertEquals(listOf(a, b, a, b, a), warm.warmedUp.map { it.single() })
    assertEquals(5, outcome.passes)
    assertEquals(2, outcome.files)
  }

  @Test
  fun `a first pass slower than the budget still leaves the JIT its passes`() {
    var passes = 0
    val warm =
      object : WarmCompileService.WarmCompiler {
        override fun compile(sources: List<String>): Map<String, Any?> = mapOf("status" to "ok")

        override fun warmUp(sources: List<String>): Map<String, Any?> {
          // The first pass builds the class-path state: on a large module, longer than the budget.
          if (passes++ == 0) Thread.sleep(200)
          return mapOf("status" to "ok")
        }

        override fun invalidate() {}
      }
    val outcome =
      WarmCompileService(
          FakeFallback(),
          { warm },
          log = {},
          warmUpSources = { listOf("/warm/WarmUp.kt") },
          warmUpMaxPasses = 4,
          warmUpBudgetMs = 100,
        )
        .warmUp()
    outcome as WarmCompileService.WarmUp.Ready
    assertEquals(4, outcome.passes)
  }

  @Test
  fun `a module file that does not compile is dropped, and the generated one used when none is left`() {
    val (good, broken) = moduleFiles("Good.kt", "Broken.kt")
    val warm =
      object : WarmCompileService.WarmCompiler {
        val warmedUp = mutableListOf<String>()

        override fun compile(sources: List<String>): Map<String, Any?> = mapOf("status" to "ok")

        override fun warmUp(sources: List<String>): Map<String, Any?> {
          warmedUp += sources.single()
          return if (sources.single() == broken) mapOf("status" to "errors", "reason" to "x")
          else mapOf("status" to "ok")
        }

        override fun invalidate() {}
      }
    warmUpService(FakeFallback(), warm, maxPasses = 4).warmUp(listOf(broken, good))
    assertEquals(listOf(broken, good, good, good), warm.warmedUp)

    warm.warmedUp.clear()
    val outcome = warmUpService(FakeFallback(), warm, maxPasses = 3).warmUp(listOf(broken))
    assertTrue(outcome is WarmCompileService.WarmUp.Ready)
    assertEquals(listOf(broken, "/warm/WarmUp.kt", "/warm/WarmUp.kt"), warm.warmedUp)
  }

  @Test
  fun `an edit that arrives during a warm-up waits for one pass, not the whole warm-up`() {
    val fallback = FakeFallback()
    lateinit var service: WarmCompileService
    var edit: Thread? = null
    val warm =
      object : WarmCompileService.WarmCompiler {
        var passes = 0
        val compiled = mutableListOf<List<String>>()

        override fun compile(sources: List<String>): Map<String, Any?> {
          compiled += sources
          return mapOf("status" to "ok")
        }

        override fun warmUp(sources: List<String>): Map<String, Any?> {
          if (passes++ == 0) {
            // An edit arrives mid-pass and blocks on the lock this pass holds.
            edit =
              Thread {
                service.compile(
                  this@WarmCompileServiceTest.sources,
                  SourceChangeSet(modified = listOf("/m/A.kt")),
                )
              }
                .apply { start() }
            while (edit!!.state != Thread.State.BLOCKED) Thread.sleep(1)
          }
          return mapOf("status" to "ok")
        }

        override fun invalidate() {}
      }
    service = warmUpService(fallback, warm, maxPasses = 50)
    val outcome = service.warmUp()
    edit!!.join(5_000)
    outcome as WarmCompileService.WarmUp.Ready
    assertEquals(1, outcome.passes)
    assertEquals(listOf(listOf("/m/A.kt")), warm.compiled)
    assertTrue(fallback.calls.isEmpty())
  }

  @Test
  fun `a warm-up that fails is reported and the next edit still compiles warm`() {
    val fallback = FakeFallback()
    val warm = FakeWarm().apply { warmUpResult = mapOf("status" to "failed", "reason" to "boom") }
    val service = service(fallback) { warm }
    val outcome = service.warmUp()
    outcome as WarmCompileService.WarmUp.Unavailable
    assertTrue(outcome.reason, outcome.reason.contains("boom"))

    service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    assertEquals(listOf(listOf("/m/A.kt")), warm.compiled)
  }

  @Test
  fun `a warm-up with no warm compiler for this Kotlin says why`() {
    val service = service(FakeFallback()) { error("no warm compiler is bundled for Kotlin 2.4.20") }
    val outcome = service.warmUp()
    outcome as WarmCompileService.WarmUp.Unavailable
    assertEquals("no warm compiler is bundled for Kotlin 2.4.20", outcome.reason)
    // Cached: asking again does not try to load it again.
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.Unavailable)
  }

  private fun fallbackWarmUpService(
    fallback: FakeFallback,
    warm: () -> WarmCompileService.WarmCompiler,
    fallbackWarmUp: () -> String?,
  ) = WarmCompileService(fallback, warm, log = {}, fallbackWarmUp = fallbackWarmUp)

  @Test
  fun `with no warm compiler, a warm-up warms the Build Tools API fallback instead, once`() {
    var warmUps = 0
    val service =
      fallbackWarmUpService(
        FakeFallback(),
        { error("no warm compiler is bundled for Kotlin 2.3 (2.3.20)") },
      ) {
        warmUps++
        null
      }
    val outcome = service.warmUp()
    outcome as WarmCompileService.WarmUp.FallbackReady
    assertEquals("no warm compiler is bundled for Kotlin 2.3 (2.3.20)", outcome.reason)
    assertEquals(1, warmUps)
    // Already warm: a second request answers at once.
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.FallbackReady)
    assertEquals(1, warmUps)
  }

  @Test
  fun `a fallback warm-up that fails says why, and the next request tries again`() {
    var warmUps = 0
    val service =
      fallbackWarmUpService(FakeFallback(), { error("warm compiler is off") }) {
        if (warmUps++ == 0) "toolchain did not load" else null
      }
    val outcome = service.warmUp()
    outcome as WarmCompileService.WarmUp.Unavailable
    assertTrue(outcome.reason, outcome.reason.contains("warm compiler is off"))
    assertTrue(outcome.reason, outcome.reason.contains("toolchain did not load"))
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.FallbackReady)
    assertEquals(2, warmUps)
  }

  @Test
  fun `a fallback warm-up that throws is reported, not thrown`() {
    val service =
      fallbackWarmUpService(FakeFallback(), { error("warm compiler is off") }) {
        throw IllegalStateException("boom")
      }
    val outcome = service.warmUp()
    outcome as WarmCompileService.WarmUp.Unavailable
    assertTrue(outcome.reason, outcome.reason.contains("boom"))
  }

  @Test
  fun `the fallback is not warmed when the warm compiler is`() {
    var warmUps = 0
    val warm = FakeWarm()
    val service =
      WarmCompileService(
        FakeFallback(),
        { warm },
        log = {},
        warmUpSources = { listOf("/warm/WarmUp.kt") },
        warmUpMaxPasses = 1,
        fallbackWarmUp = {
          warmUps++
          null
        },
      )
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.Ready)
    assertEquals(0, warmUps)
  }

  @Test
  fun `a fallback that has already compiled is not warmed again`() {
    var warmUps = 0
    val fallback = FakeFallback()
    val service =
      fallbackWarmUpService(fallback, { error("warm compiler is off") }) {
        warmUps++
        null
      }
    service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt")))
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.FallbackReady)
    assertEquals(0, warmUps)
    assertEquals(1, fallback.calls.size)
  }

  @Test
  fun `a client that never asks for a warm-up never warms the fallback`() {
    var warmUps = 0
    val fallback = FakeFallback()
    val service =
      fallbackWarmUpService(fallback, { error("warm compiler is off") }) {
        warmUps++
        null
      }
    repeat(3) { service.compile(sources, SourceChangeSet(modified = listOf("/m/A.kt"))) }
    assertEquals(0, warmUps)
    assertEquals(3, fallback.calls.size)
  }

  @Test
  fun `a warm compiler a link error turns off during its warm-up hands over to the fallback`() {
    var warmUps = 0
    val warm =
      FakeWarm().apply {
        warmUpResult =
          mapOf("status" to "failed", "reason" to "java.lang.NoSuchMethodError: 'void Foo.bar()'")
      }
    val service =
      WarmCompileService(
        FakeFallback(),
        { warm },
        log = {},
        warmUpSources = { listOf("/warm/WarmUp.kt") },
        fallbackWarmUp = {
          warmUps++
          null
        },
      )
    assertTrue(service.warmUp() is WarmCompileService.WarmUp.FallbackReady)
    assertEquals(1, warmUps)
  }

  @Test
  fun `the service from system properties warms its own fallback, with the warm compiler on or off`() {
    val dir = java.nio.file.Files.createTempDirectory("warm-sysprops")
    val configured =
      mapOf(
        DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH to "/bta/impl.jar",
        DefaultBtaCompileService.SYSPROP_OUTPUT_DIR to dir.resolve("classes").toString(),
        DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR to dir.resolve("ic").toString(),
        DefaultBtaCompileService.SYSPROP_MODULE_NAME to "m",
      )
    val name = DaemonPropertiesNames.WARM_COMPILE
    val previous = System.getProperty(name)
    try {
      for (value in listOf(null, "false")) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        assertTrue(WarmCompileService.fromSysprops(configured::get) is WarmCompileService)
      }
      assertEquals(null, WarmCompileService.fromSysprops(emptyMap<String, String>()::get))
      val ineligible =
        configured + (DefaultBtaCompileService.SYSPROP_INELIGIBILITY_REASON to "KSP detected")
      assertTrue(WarmCompileService.fromSysprops(ineligible::get) is DefaultBtaCompileService)
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `the fallback warm-up seeds the scratch snapshots and moves only new ones back`() {
    val root = java.nio.file.Files.createTempDirectory("warm-snapshots")
    try {
      val shared = java.nio.file.Files.createDirectories(root.resolve("shared"))
      val scratch = java.nio.file.Files.createDirectories(root.resolve("scratch"))
      java.nio.file.Files.writeString(shared.resolve("aaa.bin"), "old")
      BtaFallbackWarmUp.seedSnapshots(from = shared, into = scratch)
      assertEquals("old", java.nio.file.Files.readString(scratch.resolve("aaa.bin")))

      java.nio.file.Files.writeString(scratch.resolve("bbb.bin"), "new")
      // Same content address, different bytes: must never replace the module's own.
      java.nio.file.Files.delete(scratch.resolve("aaa.bin"))
      java.nio.file.Files.writeString(scratch.resolve("aaa.bin"), "other")
      BtaFallbackWarmUp.publishSnapshots(from = scratch, into = shared)
      assertEquals("old", java.nio.file.Files.readString(shared.resolve("aaa.bin")))
      assertEquals("new", java.nio.file.Files.readString(shared.resolve("bbb.bin")))
      assertEquals(
        listOf("aaa.bin", "bbb.bin"),
        shared.toFile().list()!!.sorted(),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a warm compiler that throws mid-compile falls back and is reset`() {
    val fallback = FakeFallback()
    val warm =
      object : WarmCompileService.WarmCompiler {
        var invalidated = false

        override fun compile(sources: List<String>): Map<String, Any?> =
          throw IllegalStateException("boom")

        override fun warmUp(sources: List<String>): Map<String, Any?> = mapOf("status" to "ok")

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

        override fun warmUp(sources: List<String>): Map<String, Any?> = mapOf("status" to "ok")

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
