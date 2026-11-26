package ee.schimke.composeai.daemon

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Defer discovery until a render notification or the save's own watchdog deadline. Render
 * completion captures a sequence boundary before publishing the frame, then drains only saves up to
 * that boundary. A client reacting to that frame may enqueue another save while the render watcher
 * is finishing history work; that newer save must wait for its own render/timer. Watchdogs claim
 * only their own entry, so an old timer cannot drain a newer save early.
 *
 * Repeated saves of the same path while it is still pending coalesce into a single entry carrying
 * the latest sequence, superseding any earlier pending entry (and rendering its watchdog a no-op).
 */
internal class DeferredDiscoveryQueue(
  private val watchdogMs: Long,
  private val runForPath: (String) -> Unit,
  /**
   * Schedules [action] to run after [delayMs] milliseconds. The default uses a shared single-thread
   * daemon [ScheduledExecutorService]. Tests inject a synchronous scheduler so [enqueue] / [drain]
   * interleavings can be asserted without real wall-clock waits.
   */
  private val watchdogScheduler: (delayMs: Long, action: () -> Unit) -> Unit =
    ::defaultWatchdogScheduler,
) {
  private class Entry(val sequence: Long, val path: String)

  private val sequence = AtomicLong()
  private val lock = Any()
  private val pending = LinkedHashMap<String, Entry>()

  /**
   * Queue [path], coalescing with any already-pending entry for the same path. The newer save gets
   * a fresh sequence and supersedes the prior entry so a burst of rapid saves triggers one scan.
   */
  fun enqueue(path: String) {
    val entry = Entry(sequence.incrementAndGet(), path)
    synchronized(lock) {
      pending.remove(path)
      pending[path] = entry
    }
    watchdogScheduler(watchdogMs) {
      val claimed =
        synchronized(lock) {
          if (pending[path] === entry) {
            pending.remove(path)
            true
          } else {
            false
          }
        }
      if (claimed) runForPath(entry.path)
    }
  }

  /** Capture before publishing renderFinished, so subsequent saves belong to a later render. */
  fun currentSequence(): Long = sequence.get()

  fun drain() = drainThrough(currentSequence())

  /** Claim eligible entries atomically; concurrent drains/timers cannot run an entry twice. */
  fun drainThrough(boundary: Long) {
    val ready =
      synchronized(lock) {
        val claimed = mutableListOf<Entry>()
        val it = pending.values.iterator()
        while (it.hasNext()) {
          val entry = it.next()
          if (entry.sequence <= boundary) {
            it.remove()
            claimed.add(entry)
          }
        }
        claimed
      }
    for (entry in ready) {
      runForPath(entry.path)
    }
  }

  /** Test/observability hook — current queue depth. */
  fun pendingCount(): Int = synchronized(lock) { pending.size }
}

/**
 * Executes incremental discovery scans strictly one at a time per daemon while coalescing duplicate
 * paths submitted while a scan is in flight.
 */
internal class SerializedDiscoveryRunner(
  private val runScan: (String) -> Unit,
  private val launchWorker: (Runnable) -> Unit = ::defaultDiscoveryWorkerLauncher,
) {
  private val lock = Any()
  private val pending = LinkedHashSet<String>()
  private var workerActive = false

  fun submit(path: String) {
    val startWorker =
      synchronized(lock) {
        pending.add(path)
        if (!workerActive) {
          workerActive = true
          true
        } else {
          false
        }
      }
    if (startWorker) {
      try {
        launchWorker(Runnable(::drainLoop))
      } catch (t: Throwable) {
        synchronized(lock) { workerActive = false }
        throw t
      }
    }
  }

  private fun drainLoop() {
    while (true) {
      val next =
        synchronized(lock) {
          val it = pending.iterator()
          if (!it.hasNext()) {
            workerActive = false
            return
          }
          val path = it.next()
          it.remove()
          path
        }
      try {
        runScan(next)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: incrementalDiscovery worker failed " +
            "(${t.javaClass.simpleName}: ${t.message})",
        )
        t.printStackTrace(System.err)
      }
    }
  }
}

private val watchdogExecutor: ScheduledExecutorService by lazy {
  Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "compose-ai-daemon-discovery-watchdog").apply { isDaemon = true }
  }
}

private fun defaultWatchdogScheduler(delayMs: Long, action: () -> Unit) {
  watchdogExecutor.schedule(
    {
      try {
        action()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: discovery watchdog failed (${t.javaClass.simpleName}: ${t.message})",
        )
      }
    },
    delayMs,
    TimeUnit.MILLISECONDS,
  )
}

private fun defaultDiscoveryWorkerLauncher(task: Runnable) {
  Thread(task, "compose-ai-daemon-incremental-discovery").apply { isDaemon = true }.start()
}
