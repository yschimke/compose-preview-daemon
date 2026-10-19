package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.DaemonSocketFrontEnd.Companion.inputStream
import ee.schimke.composeai.daemon.DaemonSocketFrontEnd.Companion.outputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * [DaemonSocketFrontEnd] in front of a real [JsonRpcServer]: successive clients share one server
 * (and so one host), each with its own handshake and request ids (compose-ai-tools#6104).
 */
class DaemonSocketFrontEndTest {

  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  private val dir: Path = Files.createTempDirectory("cpd")
  private val socket: Path = dir.resolve("d.sock")
  private val exitCode = AtomicInteger(-1)
  private val exited = CountDownLatch(1)

  @After
  fun cleanUp() {
    dir.toFile().deleteRecursively()
  }

  private fun startDaemon(keepAliveIdleMs: Long = 60_000L) {
    val frontEnd = DaemonSocketFrontEnd(socket, keepAliveIdleMs, log = {})
    frontEnd.start()
    val server =
      JsonRpcServer(
        input = frontEnd.serverInput,
        output = frontEnd.serverOutput,
        host = FakeRenderHost(androidSdkToAdvertise = 35),
        daemonVersion = "test",
        onExit = { code ->
          exitCode.set(code)
          exited.countDown()
        },
      )
    Thread({ server.run() }, "front-end-test-server").apply { isDaemon = true }.start()
  }

  @Test
  fun `successive clients share one server and each sees its own ids`() {
    startDaemon()

    val first = Client(socket)
    val firstInit = first.request(1, "initialize", INITIALIZE_PARAMS)
    first.notify("initialized")
    assertEquals("preview-A", first.render(2, "preview-A"))
    first.request(3, "shutdown")
    first.notify("exit")
    first.close()

    val second = Client(socket)
    val secondInit = second.request(1, "initialize", INITIALIZE_PARAMS)
    // The same process answered both handshakes: the second was replayed, not re-run.
    assertEquals(firstInit["result"], secondInit["result"])
    second.notify("initialized")
    assertEquals("preview-B", second.render(2, "preview-B"))
    second.request(3, DaemonSocketFrontEnd.STOP_METHOD)
    second.close()

    assertTrue("daemon should exit after daemon/stop", exited.await(30, TimeUnit.SECONDS))
    assertEquals(0, exitCode.get())
    assertFalse("the socket file goes with the daemon", Files.exists(socket))
  }

  @Test
  fun `shutdown is answered only after the client's renders finish`() {
    startDaemon()
    val client = Client(socket)
    client.request(1, "initialize", INITIALIZE_PARAMS)
    client.notify("initialized")
    // Shut down straight after queueing, without waiting for the render.
    client.send(2, "renderNow", """{"previews":["preview-A"],"tier":"fast"}""")
    client.send(3, "shutdown")
    val order = client.collectUntil { it["id"]?.jsonPrimitive?.intOrNull == 3 }
    val finished = order.indexOfFirst {
      it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
    }
    assertTrue("renderFinished must precede the shutdown reply: $order", finished >= 0)
    client.close()
  }

  @Test
  fun `a later client with different initialize options is refused`() {
    startDaemon()
    Client(socket).apply {
      request(1, "initialize", INITIALIZE_PARAMS)
      notify("initialized")
      close()
    }
    val other = Client(socket)
    val reply =
      other.request(
        1,
        "initialize",
        INITIALIZE_PARAMS.replace("\"metrics\":false", "\"metrics\":true"),
      )
    assertNotNull("different options must be an error: $reply", reply["error"])
    other.close()
  }

  @Test
  fun `held sessions are refused over the socket`() {
    startDaemon()
    val client = Client(socket)
    client.request(1, "initialize", INITIALIZE_PARAMS)
    val reply = client.request(2, "interactive/start", """{"previewId":"preview-A"}""")
    assertNotNull("interactive/start must be refused: $reply", reply["error"])
    client.close()
  }

  @Test
  fun `an idle daemon shuts itself down`() {
    startDaemon(keepAliveIdleMs = 200L)
    Client(socket).apply {
      request(1, "initialize", INITIALIZE_PARAMS)
      notify("initialized")
      close()
    }
    assertTrue("daemon should exit once idle", exited.await(30, TimeUnit.SECONDS))
    assertEquals(0, exitCode.get())
  }

  private class Client(socket: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val channel =
      SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        connect(UnixDomainSocketAddress.of(socket))
      }
    private val output = channel.outputStream()
    private val received = LinkedBlockingQueue<JsonObject>()

    init {
      val framer = ContentLengthFramer(channel.inputStream())
      Thread(
          {
            try {
              while (true) {
                val frame = framer.readFrame() ?: break
                received.put(json.parseToJsonElement(frame.decodeToString()).jsonObject)
              }
            } catch (_: Throwable) {
              // Closed.
            }
          },
          "front-end-test-client",
        )
        .apply { isDaemon = true }
        .start()
    }

    fun request(id: Int, method: String, params: String = "{}"): JsonObject {
      write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
      return awaitMessage { it["id"]?.jsonPrimitive?.intOrNull == id && it["method"] == null }
    }

    /** Sends a request without waiting for its reply. */
    fun send(id: Int, method: String, params: String = "{}") =
      write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    /** Every message up to and including the first [last] matches, in arrival order. */
    fun collectUntil(last: (JsonObject) -> Boolean): List<JsonObject> {
      val seen = mutableListOf<JsonObject>()
      val deadline = System.currentTimeMillis() + 20_000
      while (System.currentTimeMillis() < deadline) {
        val next = received.poll(200, TimeUnit.MILLISECONDS) ?: continue
        seen += next
        if (last(next)) return seen
      }
      throw AssertionError("message never arrived; saw $seen")
    }

    fun notify(method: String) = write("""{"jsonrpc":"2.0","method":"$method","params":{}}""")

    /** Renders [preview] and returns the id its `renderFinished` names. */
    fun render(id: Int, preview: String): String? {
      val ack = request(id, "renderNow", """{"previews":["$preview"],"tier":"fast"}""")
      assertTrue("$ack", ack["result"].toString().contains(preview))
      val finished = awaitMessage {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" &&
          it["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == preview
      }
      return finished["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    fun close() = channel.close()

    private fun write(text: String) {
      val payload = text.encodeToByteArray()
      output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
      output.write(payload)
      output.flush()
    }

    private fun awaitMessage(matcher: (JsonObject) -> Boolean): JsonObject {
      val deadline = System.currentTimeMillis() + 20_000
      while (System.currentTimeMillis() < deadline) {
        val next = received.poll(200, TimeUnit.MILLISECONDS) ?: continue
        if (matcher(next)) return next
      }
      throw AssertionError("message never arrived")
    }
  }

  private companion object {
    const val INITIALIZE_PARAMS =
      """{"protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp","moduleId":":test",""" +
        """"moduleProjectDir":"/tmp","capabilities":{"visibility":true,"metrics":false}}"""
  }
}
