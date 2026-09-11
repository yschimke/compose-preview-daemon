package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxLifecycleTest {
  @Test
  fun `sampled metrics request GC only for fresh samples and reset restarts cadence`() {
    val stats = SandboxLifecycleStats()
    var collections = 0
    val results = (1..7).map { SandboxMeasurement.collect(stats, it.toLong(), 3) { collections++ } }
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
    val next = SandboxMeasurement.collect(stats, 8L, 3) { collections++ }
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
}
