package ee.schimke.composeai.daemon

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [DesktopHost.submit]'s timeout measures the render, not the queue in front of it.
 *
 * `JsonRpcServer` enqueues a whole `renderNow` batch at once, one waiting thread per id. When the
 * deadline started at enqueue, every request in a batch longer than one timeout window failed at
 * the same instant however healthy the render thread was, and the render thread then went on to
 * render the abandoned requests for nobody.
 *
 * The render body here is the [DesktopHost] `previewSpecResolver`, which runs on the render thread
 * and returns `null` (a stub render), so a test controls exactly how long each "render" takes.
 */
class DesktopHostRenderTimeoutTest {

  private fun request(id: String) = RenderRequest.Render(target = RenderTarget.Preview(id))

  @Test
  fun `a batch longer than one timeout window completes when each render fits`() {
    val resolved = Collections.synchronizedList(mutableListOf<String>())
    val host =
      DesktopHost(
        previewSpecResolver = { id ->
          Thread.sleep(RENDER_MS)
          resolved += id
          null
        }
      )
    host.start()
    val pool = Executors.newFixedThreadPool(BATCH)
    try {
      // Every render takes RENDER_MS < TIMEOUT_MS, but the batch takes BATCH * RENDER_MS, well past
      // TIMEOUT_MS. Measured from enqueue, the tail of the batch timed out.
      val futures: List<Future<RenderResult>> =
        (1..BATCH).map { i ->
          pool.submit<RenderResult> { host.submit(request("p$i"), TIMEOUT_MS) }
        }
      futures.forEach { it.get(30, TimeUnit.SECONDS) }
      assertEquals(BATCH, resolved.size)
    } finally {
      pool.shutdownNow()
      host.shutdown()
    }
  }

  @Test
  fun `a hung render still times out, and the queue behind it is dropped rather than rendered`() {
    val resolved = Collections.synchronizedList(mutableListOf<String>())
    val release = CountDownLatch(1)
    val host =
      DesktopHost(
        previewSpecResolver = { id ->
          resolved += id
          if (id == "hung") release.await(30, TimeUnit.SECONDS)
          null
        }
      )
    host.start()
    val pool = Executors.newFixedThreadPool(2)
    try {
      val hung = pool.submit<RenderResult> { host.submit(request("hung"), TIMEOUT_MS) }
      // Let `hung` reach the render thread before queueing behind it.
      while (resolved.isEmpty()) Thread.yield()
      val queued = pool.submit<RenderResult> { host.submit(request("queued"), TIMEOUT_MS) }

      // The hung render's own deadline still fires: the timeout is a hung-render guard.
      val hungFailure = failureOf(hung)
      assertTrue(hungFailure, hungFailure.contains("of rendering"))
      // The request behind it gives up too, once the render ahead has overrun its deadline,
      // instead of waiting forever on a wedged thread.
      val queuedFailure = failureOf(queued)
      assertTrue(queuedFailure, queuedFailure.contains("gave up while queued"))

      // Unwedge. `queued` was abandoned, so the render thread must skip it: the next request is
      // the next thing it renders.
      release.countDown()
      host.submit(request("after"), 30_000)
      assertEquals(listOf("hung", "after"), resolved.toList())
    } finally {
      release.countDown()
      pool.shutdownNow()
      host.shutdown()
    }
    assertFalse(host.renderThreadInterrupted)
  }

  private fun failureOf(future: Future<RenderResult>): String =
    try {
      future.get(30, TimeUnit.SECONDS)
      fail("expected the submit to fail")
      error("unreachable")
    } catch (e: java.util.concurrent.ExecutionException) {
      e.cause?.message.orEmpty()
    }

  private companion object {
    const val BATCH = 5
    const val RENDER_MS = 300L
    const val TIMEOUT_MS = 1_000L
  }
}
