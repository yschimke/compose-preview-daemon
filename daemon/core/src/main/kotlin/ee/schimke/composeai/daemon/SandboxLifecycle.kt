package ee.schimke.composeai.daemon

import com.sun.management.OperatingSystemMXBean
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-host sandbox-lifecycle counters — feed [RenderMetrics.sandboxAgeRenders] /
 * [RenderMetrics.sandboxAgeMs] for B2.3.
 *
 * Constructed once per host lifetime ([RobolectricHost], [DesktopHost],
 * [ee.schimke.composeai.daemon.harness.FakeHost], etc.). [bumpRenderCount] is called by the engine
 * (via [SandboxMeasurement.collect]) once per render-completion. Sandbox recycle (B2.5, not yet
 * landed) will reset both counters via [reset]; until then `sandboxAgeRenders` keeps growing for
 * the whole host lifetime — documented behaviour for B2.3 v1.
 *
 * **Thread-safety.** [renderCount] is an [AtomicLong] so concurrent renders increment safely. The
 * desktop / Android backends serialise renders to a single thread, so contention is rare in
 * practice; the [AtomicLong] is cheap insurance for any future host that submits concurrently.
 */
public class SandboxLifecycleStats(
  /** Host-construction time in nanoseconds. Defaults to [System.nanoTime] at instantiation. */
  startNs: Long = System.nanoTime()
) {
  @Volatile private var startNs: Long = startNs

  private val renderCount: AtomicLong = AtomicLong(0)

  /** Wall-clock since host construction, in milliseconds. */
  public fun ageMs(): Long = (System.nanoTime() - startNs) / 1_000_000L

  /** Number of renders completed against this host so far. */
  public fun renders(): Long = renderCount.get()

  /** Bumps the render counter. Called from the engine after each render-body returns. */
  public fun bumpRenderCount(): Long = renderCount.incrementAndGet()

  /**
   * Resets both counters — wired from B2.5's recycle path once that lands. For B2.3 v1 nobody calls
   * this in production; only unit tests use it.
   */
  public fun reset() {
    startNs = System.nanoTime()
    renderCount.set(0)
  }
}

/**
 * Per-render measurement helper — collects the four B2.3 metrics into a flat `Map<String, Long>`
 * carrier so the renderer-agnostic [RenderResult.metrics] stays a free-form `Map<String, Long>?`
 * (per the B2.3 brief: "translate flat map → structured `RenderMetrics` in `JsonRpcServer`"). The
 * engine calls [collect] once per render-completion.
 *
 * **Measuring must not change what it measures.** `heapAfterGcMb` is the heap in use right after
 * the JVM's most recent garbage collection, read from the collectors' last-GC records
 * ([heapAfterLastGcMb]); nothing here requests a collection. It used to call `System.gc()` on every
 * render to make that number fresh, which put a full stop-the-world collection — about 150 ms on a
 * desktop daemon — between every frame and its `renderFinished`, while the reported `tookMs` (taken
 * before it) never showed it (docs/daemon/SAMPLED-METRICS.md). The memory that collection used to
 * reclaim is now [IdleCollector]'s job, off the render path.
 *
 * [DaemonProperties.metricsEveryRenders] still samples which renders carry the structured block;
 * the others report timing only, never a stale or mislabeled heap value.
 *
 * **`nativeHeapMb` is an approximation** — we report the JVM's committed virtual memory size (via
 * [com.sun.management.OperatingSystemMXBean.getCommittedVirtualMemorySize]), divided by 1MB. That
 * is *not* strictly the "native heap" — it covers JVM heap + native libs + mapped files — but it's
 * the closest portable proxy on HotSpot. The daemon's heaviest native-side state is Skia (desktop)
 * and Robolectric's native libs (Android), both of which show up under committed virtual memory. On
 * non-HotSpot JVMs where the cast fails we fall back to 0 with a one-time warn-log; clients see
 * `nativeHeapMb = 0` and that's clearer than a hand-waved heuristic.
 */
public object SandboxMeasurement {

  private val nonHotSpotWarned = AtomicBoolean(false)

  /**
   * Advances lifecycle counts and returns the four B2.3 keys on measured renders, or just `tookMs`
   * when the configured cadence skips measurement. The engine has already stopped its render timer
   * by the time we get called; reported duration is preserved in either case.
   *
   * The map's keys are pinned constants on [RenderMetrics.Companion] so all consumers
   * (`JsonRpcServer.renderFinishedFromResult`, the soak tests, etc.) agree on the spelling.
   */
  public fun collect(stats: SandboxLifecycleStats, tookMs: Long): Map<String, Long> {
    IdleCollector.renderCompleted()
    return collect(stats, tookMs, DaemonProperties.metricsEveryRenders.read(), ::heapAfterLastGcMb)
  }

  internal fun collect(
    stats: SandboxLifecycleStats,
    tookMs: Long,
    everyRenders: Int,
    heapAfterGcMb: () -> Long,
  ): Map<String, Long> {
    val sandboxAgeRenders = stats.bumpRenderCount()
    if ((sandboxAgeRenders - 1) % everyRenders.coerceAtLeast(1) != 0L) {
      // Do not label an ordinary heap reading as post-GC or return stale measurements.
      // The wire adapter treats timing-only maps as an absent structured metrics block.
      return mapOf("tookMs" to tookMs)
    }
    val heapMb = heapAfterGcMb()

    val nativeHeapMb: Long =
      try {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        if (osBean is OperatingSystemMXBean) {
          osBean.committedVirtualMemorySize / (1024L * 1024L)
        } else {
          // Non-HotSpot JVM (e.g. some IBM J9 builds). Documented fallback.
          if (nonHotSpotWarned.compareAndSet(false, true)) {
            System.err.println(
              "compose-ai-daemon: SandboxMeasurement: OperatingSystemMXBean is not " +
                "com.sun.management.OperatingSystemMXBean (got ${osBean.javaClass.name}); " +
                "reporting nativeHeapMb=0 for the rest of this JVM"
            )
          }
          0L
        }
      } catch (t: Throwable) {
        if (nonHotSpotWarned.compareAndSet(false, true)) {
          System.err.println(
            "compose-ai-daemon: SandboxMeasurement: failed to read committedVirtualMemorySize " +
              "(${t.javaClass.simpleName}: ${t.message}); reporting nativeHeapMb=0 for the rest " +
              "of this JVM"
          )
        }
        0L
      }

    val sandboxAgeMs = stats.ageMs()

    return mapOf(
      "tookMs" to tookMs,
      RenderMetrics.KEY_HEAP_AFTER_GC_MB to heapMb,
      RenderMetrics.KEY_NATIVE_HEAP_MB to nativeHeapMb,
      RenderMetrics.KEY_SANDBOX_AGE_RENDERS to sandboxAgeRenders,
      RenderMetrics.KEY_SANDBOX_AGE_MS to sandboxAgeMs,
    )
  }
}

/**
 * Heap in use right after the most recent garbage collection, in MB, from the HotSpot collectors'
 * last-GC records: whichever collector ran last, summed over the heap pools. A young collection's
 * record covers the old generation too, so this is the whole surviving heap. Before the first
 * collection — or on a JVM without these records — it is the heap in use now.
 */
internal fun heapAfterLastGcMb(): Long {
  val heapPools =
    ManagementFactory.getMemoryPoolMXBeans()
      .filter { it.type == java.lang.management.MemoryType.HEAP }
      .map { it.name }
      .toSet()
  val lastGc =
    ManagementFactory.getGarbageCollectorMXBeans()
      .mapNotNull { (it as? com.sun.management.GarbageCollectorMXBean)?.lastGcInfo }
      .maxByOrNull { it.endTime }
  val usedBytes =
    lastGc?.memoryUsageAfterGc?.filterKeys { it in heapPools }?.values?.sumOf { it.used }
      ?: ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
  return usedBytes / (1024L * 1024L)
}

/**
 * The memory policy the per-render `System.gc()` used to provide as a side effect of measuring:
 * once no render has completed for [DaemonProperties.idleGcMs], collect, so a quiet daemon gives
 * back what the last renders left behind. Every completed render pushes the collection back, so it
 * never lands between a save and its pixels while renders keep coming.
 */
internal object IdleCollector {
  private val executor =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "compose-ai-idle-gc").apply { isDaemon = true }
    }
  @Volatile private var pending: java.util.concurrent.ScheduledFuture<*>? = null

  fun renderCompleted(delayMs: Long = DaemonProperties.idleGcMs.read()) {
    pending?.cancel(false)
    if (delayMs <= 0) return
    pending =
      executor.schedule({ System.gc() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  }
}
