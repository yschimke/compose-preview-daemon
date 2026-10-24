package ee.schimke.composeai.daemon

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whether an in-process compile — a `compileSources` or a `compileWarmUp` — is running in this
 * daemon, so background work can keep out of its way.
 *
 * The edit loop is latency-bound and the compile is most of it; background work is throughput-bound
 * and can wait. Measured on a 4-core machine with a 5-sandbox Android pool, a `compileWarmUp` that
 * shared the CPU with the pool's background boot of sandboxes 2..N took 16-18 s and managed 4-5
 * passes, and edits compiled in 0.9-1.8 s until the pool settled, against 0.3-0.4 s afterwards. The
 * sandbox pool's background boot calls [awaitQuiet] before it starts a worker's boot or its
 * boot-time warm render, so those land between compiles instead of under them.
 *
 * Process-wide, like [StartupTimings]: one daemon serves one module, and the pool that yields and
 * the JSON-RPC handlers that compile live in different modules.
 */
public object CompileActivity {

  private val lock = ReentrantLock()
  private val changed = lock.newCondition()

  /** Compiles running now; guarded by [lock]. */
  private var active = 0

  /** [System.nanoTime] when the last compile ended; null when none has run. Guarded by [lock]. */
  private var lastEndNanos: Long? = null

  /** Whether a compile is running now. */
  public val isActive: Boolean
    get() = lock.withLock { active > 0 }

  /**
   * Runs [block] as a compile: [awaitQuiet] callers wait until it, and a quiet period, are over.
   */
  public fun <T> during(block: () -> T): T {
    begin()
    try {
      return block()
    } finally {
      end()
    }
  }

  private fun begin() {
    lock.withLock { active++ }
  }

  private fun end() {
    lock.withLock {
      active--
      lastEndNanos = System.nanoTime()
      changed.signalAll()
    }
  }

  /**
   * Blocks until no compile is running and none has ended in the last [quietMs] — the edit loop
   * renders right after its compile, so the render gets the CPU too — or until [maxWaitMs] has
   * passed, so a continuous stream of compiles cannot hold background work off forever. Returns at
   * once, with 0, when nothing is compiling and nothing compiled recently. Returns how long it
   * waited, in milliseconds.
   */
  public fun awaitQuiet(quietMs: Long, maxWaitMs: Long): Long {
    val start = System.nanoTime()
    val deadline = start + maxWaitMs.coerceAtLeast(0) * 1_000_000
    val quietNanos = quietMs.coerceAtLeast(0) * 1_000_000
    lock.withLock {
      while (true) {
        val now = System.nanoTime()
        if (now - deadline >= 0) break
        val quietFrom =
          when {
            active > 0 -> deadline
            else -> lastEndNanos?.let { it + quietNanos } ?: now
          }
        if (now - quietFrom >= 0) break
        changed.awaitNanos(minOf(quietFrom, deadline) - now)
      }
    }
    return (System.nanoTime() - start) / 1_000_000
  }

  /** Test seam: forget every compile, as in a fresh daemon. */
  internal fun resetForTest() {
    lock.withLock {
      active = 0
      lastEndNanos = null
      changed.signalAll()
    }
  }
}
