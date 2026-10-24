package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain renders start most urgent first: the focused preview, then the visible ones, then the rest,
 * at most `maxConcurrentRenders` at a time, and nothing less urgent while a focused render runs.
 * Rendering a file's seven previews at once made the edited one 3-4 times slower.
 */
class PlainRenderPriorityTest {

  private val json = Json { ignoreUnknownKeys = true }

  @After
  fun clearProperty() {
    System.clearProperty(DaemonProperties.Names.MAX_CONCURRENT_RENDERS)
  }

  @Test(timeout = 30_000)
  fun `queued renders start focused first, then visible, then the rest`() =
    withServer(maxConcurrent = 1) { host, received, send ->
      send.renderNow(2, "A")
      assertTrue(host.awaitStarted(1))
      send.notify("""{"jsonrpc":"2.0","method":"setVisible","params":{"ids":["C","D"]}}""")
      send.notify("""{"jsonrpc":"2.0","method":"setFocus","params":{"ids":["D"]}}""")
      send.renderNow(3, "B", "C", "D")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 })
      assertEquals("one at a time", listOf("A"), host.started.toList())

      repeat(4) {
        host.releaseOne()
        assertNotNull(pollUntil(received, matcher = ::isRenderFinished))
      }
      assertEquals(listOf("A", "D", "C", "B"), host.started.toList())
    }

  @Test(timeout = 30_000)
  fun `a preview asked for again while running keeps its place by request time`() =
    withServer(maxConcurrent = 1) { host, received, send ->
      send.renderNow(2, "A")
      assertTrue(host.awaitStarted(1))
      send.renderNow(3, "A")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 })
      Thread.sleep(20)
      send.renderNow(4, "B")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 4 })

      repeat(3) {
        host.releaseOne()
        assertNotNull(pollUntil(received, matcher = ::isRenderFinished))
      }
      assertEquals(listOf("A", "A", "B"), host.started.toList())
    }

  @Test(timeout = 30_000)
  fun `nothing less urgent starts while a focused render runs`() =
    withServer(maxConcurrent = 3) { host, received, send ->
      send.notify("""{"jsonrpc":"2.0","method":"setFocus","params":{"ids":["F"]}}""")
      send.renderNow(2, "F", "X", "Y")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 })
      assertTrue(host.awaitStarted(1))
      Thread.sleep(300)
      assertEquals("only the focused render runs", listOf("F"), host.started.toList())

      host.releaseOne()
      assertTrue("the rest start together once it finishes", host.awaitStarted(3))
      assertEquals(setOf("X", "Y"), host.started.drop(1).toSet())
      host.releaseOne()
      host.releaseOne()
      repeat(3) { assertNotNull(pollUntil(received, matcher = ::isRenderFinished)) }
    }

  private class Sender(private val out: PipedOutputStream) {
    fun notify(body: String) {
      val payload = body.toByteArray(Charsets.UTF_8)
      out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
      out.write(payload)
      out.flush()
    }

    fun renderNow(id: Int, vararg previews: String) =
      notify(
        """{"jsonrpc":"2.0","id":$id,"method":"renderNow","params":{
              "previews":[${previews.joinToString(",") { "\"$it\"" }}],"tier":"fast"}}"""
      )
  }

  private fun isRenderFinished(msg: JsonObject): Boolean =
    msg["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"

  private fun withServer(
    maxConcurrent: Int,
    body: (OrderedHost, LinkedBlockingQueue<JsonObject>, Sender) -> Unit,
  ) {
    System.setProperty(DaemonProperties.Names.MAX_CONCURRENT_RENDERS, maxConcurrent.toString())
    val tmp = Files.createTempDirectory("plain-priority").toFile()
    val png = File(tmp, "p.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2)) }
    val host = OrderedHost(png)

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread = Thread({ server.run() }, "plain-priority-server").apply { isDaemon = true }
    serverThread.start()
    val received = LinkedBlockingQueue<JsonObject>()
    val reader = ContentLengthFramer(serverToClientIn)
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              received.put(json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject)
            }
          } catch (_: Throwable) {}
        },
        "plain-priority-reader",
      )
      .apply { isDaemon = true }
      .start()
    val send = Sender(clientToServerOut)

    try {
      send.notify(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}"""
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      send.notify("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      body(host, received, send)

      send.notify("""{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      send.notify("""{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      host.releaseAll()
      runCatching { clientToServerOut.close() }
      runCatching { serverToClientIn.close() }
      serverThread.join(5_000)
    }
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 5_000,
    matcher: (JsonObject) -> Boolean,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
      if (matcher(msg)) return msg
    }
    return null
  }
}

/** A host that records the order renders start in, each blocking until [releaseOne]. */
private class OrderedHost(private val png: File) : RenderHost {
  val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val startedCount = Semaphore(0)
  private val permits = Semaphore(0)

  fun awaitStarted(count: Int): Boolean {
    val deadline = System.currentTimeMillis() + 5_000
    while (started.size < count && System.currentTimeMillis() < deadline) {
      startedCount.tryAcquire(50, TimeUnit.MILLISECONDS)
    }
    return started.size >= count
  }

  fun releaseOne() = permits.release()

  fun releaseAll() = permits.release(100)

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    started += (request.target as RenderTarget.Preview).previewId
    startedCount.release()
    permits.tryAcquire(10, TimeUnit.SECONDS)
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "ordered",
      artifact = RenderArtifact(png.absolutePath),
    )
  }

  override fun shutdown(timeoutMs: Long) {}
}
