package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Defer discovery until a render notification or the save's own watchdog deadline. Render
 * completion captures a sequence boundary before publishing the frame, then drains only saves up to
 * that boundary. A client reacting to that frame may enqueue another save while the render watcher
 * is finishing history work; that newer save must wait for its own render/timer. Watchdogs claim
 * only their own entry, so an old timer cannot drain a newer save early.
 */
internal class DeferredDiscoveryQueue(
  private val watchdogMs: Long,
  private val runForPath: (String) -> Unit,
  /**
   * Schedules [action] to run after [delayMs] milliseconds. The default uses a fresh daemon thread
   * per enqueue, matching the prior inline implementation. Tests inject a synchronous scheduler so
   * [enqueue] / [drain] interleavings can be asserted without real wall-clock waits.
   */
  private val watchdogScheduler: (delayMs: Long, action: () -> Unit) -> Unit =
    ::defaultWatchdogScheduler,
) {
  private class Entry(val sequence: Long, val path: String)

  private val sequence = AtomicLong()
  private val pending = ConcurrentLinkedQueue<Entry>()

  /** Queue a distinct entry even when successive saves have the same path. */
  fun enqueue(path: String) {
    val entry = Entry(sequence.incrementAndGet(), path)
    pending.add(entry)
    watchdogScheduler(watchdogMs) { if (pending.remove(entry)) runForPath(entry.path) }
  }

  /** Capture before publishing renderFinished, so subsequent saves belong to a later render. */
  fun currentSequence(): Long = sequence.get()

  fun drain() = drainThrough(currentSequence())

  /** Claim eligible entries atomically; concurrent drains/timers cannot run an entry twice. */
  fun drainThrough(boundary: Long) {
    for (entry in pending) {
      if (entry.sequence <= boundary && pending.remove(entry)) runForPath(entry.path)
    }
  }

  /** Test/observability hook — current queue depth. */
  fun pendingCount(): Int = pending.size
}

private fun defaultWatchdogScheduler(delayMs: Long, action: () -> Unit) {
  Thread(
      {
        try {
          Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return@Thread
        }
        action()
      },
      "compose-ai-daemon-discovery-watchdog",
    )
    .apply { isDaemon = true }
    .start()
}
