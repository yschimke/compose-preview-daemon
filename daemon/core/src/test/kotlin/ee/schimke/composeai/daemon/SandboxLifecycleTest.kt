package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxLifecycleTest {
  @Test
  fun `sampled metrics read the heap only for fresh samples and reset restarts cadence`() {
    val stats = SandboxLifecycleStats()
    var collections = 0
    val results =
      (1..7).map { SandboxMeasurement.collect(stats, it.toLong(), 3) { collections++.toLong() } }
    assertEquals(3, collections)
    assertEquals(7L, stats.renders())
    for ((index, result) in results.withIndex()) {
      assertEquals((index + 1).toLong(), result["tookMs"])
      if (index % 3 == 0) {
        assertEquals((index + 1).toLong(), result[RenderMetrics.KEY_SANDBOX_AGE_RENDERS])
        assertTrue(RenderMetrics.fromFlatMap(result) is RenderMetrics.FromFlatMapResult.Populated)
      } else {
        assertEquals(setOf("tookMs"), result.keys)
      }
    }
    stats.reset()
    val next = SandboxMeasurement.collect(stats, 8L, 3) { collections++.toLong() }
    assertEquals(4, collections)
    assertEquals(1L, next[RenderMetrics.KEY_SANDBOX_AGE_RENDERS])
  }

  @Test
  fun `stats count renders and reset both counters`() {
    val stats = SandboxLifecycleStats(System.nanoTime() - 25_000_000L)

    assertTrue(stats.ageMs() >= 20)
    assertEquals(0, stats.renders())
    assertEquals(1, stats.bumpRenderCount())
    assertEquals(2, stats.bumpRenderCount())

    stats.reset()

    assertEquals(0, stats.renders())
    assertTrue(stats.ageMs() < 1_000)
  }

  @Test
  fun `measurement reports the complete wire contract and advances sandbox age`() {
    val stats = SandboxLifecycleStats()

    val first = SandboxMeasurement.collect(stats, tookMs = 17)
    val second = SandboxMeasurement.collect(stats, tookMs = 23)

    assertEquals(
      setOf(
        "tookMs",
        RenderMetrics.KEY_HEAP_AFTER_GC_MB,
        RenderMetrics.KEY_NATIVE_HEAP_MB,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS,
        RenderMetrics.KEY_SANDBOX_AGE_MS,
      ),
      first.keys,
    )
    assertEquals(17L, first["tookMs"])
    assertEquals(1L, first[RenderMetrics.KEY_SANDBOX_AGE_RENDERS])
    assertEquals(2L, second[RenderMetrics.KEY_SANDBOX_AGE_RENDERS])
    assertTrue(first.getValue(RenderMetrics.KEY_HEAP_AFTER_GC_MB) >= 0)
    assertTrue(first.getValue(RenderMetrics.KEY_NATIVE_HEAP_MB) >= 0)
  }

  @Test
  fun `measuring does not collect garbage`() {
    val before = gcCount()
    repeat(5) { SandboxMeasurement.collect(SandboxLifecycleStats(), tookMs = 1) }
    // Allocation may trigger an ordinary young collection, but five explicit full collections
    // would show up as at least five; a JVM idle enough to run none is the common case.
    assertTrue("measurement requested collections", gcCount() - before < 5)
  }

  @Test
  fun `heap after the last GC is reported without requesting one`() {
    System.gc()
    val after = heapAfterLastGcMb()
    assertTrue(after > 0)
    val used =
      java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
    assertTrue("post-GC heap $after MB above current use $used MB plus slack", after <= used + 64)
  }

  @Test
  fun `the idle collector collects once renders stop, and each render pushes it back`() {
    val before = gcCount()
    IdleCollector.renderCompleted(delayMs = 400)
    Thread.sleep(150)
    IdleCollector.renderCompleted(delayMs = 400)
    Thread.sleep(150)
    assertEquals("collected while renders were still arriving", before, gcCount())
    Thread.sleep(600)
    assertTrue("no collection after renders stopped", gcCount() > before)
  }

  private fun gcCount(): Long =
    java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf {
      it.collectionCount.coerceAtLeast(0)
    }
}
