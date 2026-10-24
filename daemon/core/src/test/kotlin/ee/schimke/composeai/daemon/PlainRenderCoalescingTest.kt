package ee.schimke.composeai.daemon

import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A burst of saves sends a plain `renderNow` per save for the same previews. Each used to become
 * its own render, all contending for the preview's sandbox, so the last save's render waited behind
 * every earlier one. While a plain render of a preview is in flight, further plain `renderNow`s for
 * it now collapse into one render after it.
 */
class PlainRenderCoalescingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun `plain renderNows for a preview in flight collapse into one render after it`() =
    withServer { host, received, send ->
      send.renderNow(2)
      assertTrue("first render started", host.firstStarted.await(5, TimeUnit.SECONDS))
      // Three more saves while the first render runs: each is accepted as queued.
      for (id in 3..5) {
        send.renderNow(id)
        val result =
          pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == id }!!["result"]!!.jsonObject
        assertEquals(
          listOf("preview-A"),
          result["queued"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
      }
      assertEquals("nothing else starts while the first runs", 1, host.renders.get())

      host.release()
      assertNotNull("the first render finishes", pollUntil(received, matcher = ::isRenderFinished))
      assertNotNull("one render follows it", pollUntil(received, matcher = ::isRenderFinished))
      assertNull(
        "and only one",
        pollUntil(received, timeoutMs = 1_000, matcher = ::isRenderFinished),
      )
      assertEquals(2, host.renders.get())

      // Once idle, a renderNow renders again straight away.
      send.renderNow(6)
      assertNotNull(pollUntil(received, matcher = ::isRenderFinished))
      assertEquals(3, host.renders.get())
    }

  @Test(timeout = 30_000)
  fun `shutdown drains a collapsed renderNow accepted before it`() =
    withServer { host, received, send ->
      send.renderNow(2)
      assertTrue("first render started", host.firstStarted.await(5, TimeUnit.SECONDS))
      send.renderNow(3)
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 })

      send.frame("""{"jsonrpc":"2.0","id":98,"method":"shutdown"}""")
      assertNull(
        "shutdown waits for the render in flight",
        pollUntil(received, timeoutMs = 300) { it["id"]?.jsonPrimitive?.intOrNull == 98 },
      )
      host.release()

      val beforeResponse = mutableListOf<JsonObject>()
      assertNotNull(
        "shutdown answers",
        pollUntil(received) {
          beforeResponse += it
          it["id"]?.jsonPrimitive?.intOrNull == 98
        },
      )
      assertEquals(
        "both accepted renders finish before the shutdown response",
        2,
        beforeResponse.count(::isRenderFinished),
      )
      assertEquals(2, host.renders.get())
    }

  private class Sender(private val out: PipedOutputStream) {
    fun frame(body: String) {
      val payload = body.toByteArray(Charsets.UTF_8)
      out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
      out.write(payload)
      out.flush()
    }

    fun renderNow(id: Int) =
      frame(
        """{"jsonrpc":"2.0","id":$id,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast"}}"""
      )
  }

  private fun isRenderFinished(msg: JsonObject): Boolean =
    msg["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"

  /** Runs [body] against an initialized server over [GatedHost], then shuts it down. */
  private fun withServer(body: (GatedHost, LinkedBlockingQueue<JsonObject>, Sender) -> Unit) {
    val tmp = Files.createTempDirectory("plain-coalesce").toFile()
    val png = File(tmp, "preview-A.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2)) }
    val host = GatedHost(png)

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
    val serverThread = Thread({ server.run() }, "plain-coalesce-server").apply { isDaemon = true }
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
        "plain-coalesce-reader",
      )
      .apply { isDaemon = true }
      .start()
    val send = Sender(clientToServerOut)

    try {
      send.frame(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}"""
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      send.frame("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      body(host, received, send)

      send.frame("""{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      send.frame("""{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      host.release()
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

/** A host whose first render blocks until [release]; later renders return at once. */
private class GatedHost(private val png: File) : RenderHost {
  val renders = AtomicInteger()
  val firstStarted = CountDownLatch(1)
  private val gate = CountDownLatch(1)

  fun release() = gate.countDown()

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    if (renders.incrementAndGet() == 1) {
      firstStarted.countDown()
      gate.await(10, TimeUnit.SECONDS)
    }
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "gated",
      artifact = RenderArtifact(png.absolutePath),
    )
  }

  override fun shutdown(timeoutMs: Long) {}
}
