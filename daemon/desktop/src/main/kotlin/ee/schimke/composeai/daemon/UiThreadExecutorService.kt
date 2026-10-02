package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.DesktopUiThread
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * An [ExecutorService] whose tasks run, in [delegate]'s order and on [delegate]'s schedule, with
 * their bodies hopped onto the AWT event dispatch thread through [DesktopUiThread].
 *
 * A held interactive scene keeps its own single-thread executor for ordering and lifecycle — one
 * task at a time, shut down with the session — while the scene work itself runs where Compose
 * Desktop posts the scene's delayed `RectManager` dispatch, so the two can never interleave. The
 * executor thread blocks for the duration of each hop. Lifecycle calls go straight to [delegate].
 */
internal class UiThreadExecutorService(private val delegate: ExecutorService) :
  AbstractExecutorService() {

  override fun execute(command: Runnable) {
    delegate.execute { DesktopUiThread.run { command.run() } }
  }

  override fun shutdown() = delegate.shutdown()

  override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

  override fun isShutdown(): Boolean = delegate.isShutdown

  override fun isTerminated(): Boolean = delegate.isTerminated

  override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
    delegate.awaitTermination(timeout, unit)
}
