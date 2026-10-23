package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderTarget
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `compileSources` answers once `swapUserClassLoaders` returns, so the swap must not wait for
 * renders queued on a worker. A fake spare worker on a loopback socket stands in for the worker
 * JVM: it answers `configure`, records each request, and holds a render until released.
 */
class SandboxProcessPoolSwapTest {

  private val requests = Collections.synchronizedList(mutableListOf<String>())
  private val renderStarted = CountDownLatch(1)
  private val releaseRender = CountDownLatch(1)
  private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
  private val fakeWorker = thread(isDaemon = true) { serve() }

  private fun serve() {
    server.accept().use { socket ->
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
      var renders = 0
      while (true) {
        val line = reader.readLine() ?: return
        val request = workerJson.decodeFromString(WorkerRequest.serializer(), line)
        requests += request.javaClass.simpleName
        val response: WorkerResponse =
          when (request) {
            is WorkerRequest.Configure -> WorkerResponse.Configured(pid = 4242)
            is WorkerRequest.Render -> {
              if (renders++ == 0) {
                renderStarted.countDown()
                releaseRender.await(10, TimeUnit.SECONDS)
              }
              WorkerResponse.Result(RenderResultDto(request.id, 0, "fake"))
            }
            else -> WorkerResponse.Ok
          }
        writer.write(workerJson.encodeToString(WorkerResponse.serializer(), response))
        writer.write("\n")
        writer.flush()
      }
    }
  }

  private val pool =
    SandboxProcessPool(
      workerCount = 1,
      bootTimeoutMs = 5_000,
      spareEndpoints = listOf(server.localPort),
    )

  @After
  fun tearDown() {
    releaseRender.countDown()
    runCatching { pool.shutdown(1_000) }
    server.close()
  }

  private fun render(id: Long) =
    pool.submit(0, RenderRequest.Render(id = id, target = RenderTarget.Stub("p$id")), 10_000)

  @Test
  fun `a swap does not wait for a render in flight, and the next render swaps first`() {
    assertTrue(pool.adoptSpare(0))
    val inFlight = thread { render(1) }
    assertTrue(renderStarted.await(5, TimeUnit.SECONDS))

    val start = System.nanoTime()
    pool.swapUserClassLoaders()
    val swapMs = (System.nanoTime() - start) / 1_000_000
    assertTrue("the swap waited ${swapMs} ms for the render in flight", swapMs < 1_000)

    releaseRender.countDown()
    inFlight.join(5_000)
    render(2)
    assertEquals(listOf("Configure", "Render", "Swap", "Render"), requests.toList())
  }

  @Test
  fun `an idle worker swaps at once, and only once per swap`() {
    assertTrue(pool.adoptSpare(0))
    releaseRender.countDown()
    pool.swapUserClassLoaders()
    render(1)
    render(2)
    assertEquals(listOf("Configure", "Swap", "Render", "Render"), requests.toList())
  }
}
