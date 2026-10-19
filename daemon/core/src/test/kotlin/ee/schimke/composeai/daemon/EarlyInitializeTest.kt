package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.DaemonSocketFrontEnd.Companion.inputStream
import ee.schimke.composeai.daemon.DaemonSocketFrontEnd.Companion.outputStream
import ee.schimke.composeai.daemon.protocol.BootPhase
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * compose-ai-tools#6114 — an `initialize` answered before the host is up, for a client that opts in
 * with `capabilities.bootProgress`, and exactly the old behaviour for one that does not.
 */
class EarlyInitializeTest {

  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  private val gate = CountDownLatch(1)
  private val dir: Path = Files.createTempDirectory("cpd-early")

  @After
  fun cleanUp() {
    gate.countDown()
    dir.toFile().deleteRecursively()
  }

  @Test
  fun `an opted-in client is answered while the host boots and its render is queued`() {
    val daemon = Daemon(SlowStartHost(gate))
    val client = daemon.client

    val init = client.request(1, "initialize", initializeParams(bootProgress = true))
    assertEquals(true, init.result()?.get("booting")?.jsonPrimitive?.booleanOrNull)
    client.notify("initialized")
    client.awaitBoot(BootPhase.STARTING)
    client.awaitBoot(BootPhase.SANDBOX_BOOTING)

    // Queued, not refused: no reply while the host is still down.
    client.send(2, "renderNow", """{"previews":["preview-A"],"tier":"fast"}""")
    assertNull("renderNow must wait for the host", client.poll(500) { it.id() == 2 })

    gate.countDown()
    val order = client.collectUntil { it.method() == "renderFinished" }
    val ready = order.indexOfFirst { it.bootPhase() == BootPhase.READY }
    val reply = order.indexOfFirst { it.id() == 2 }
    assertTrue(
      "ready must come before the queued renderNow's reply: $order",
      ready in 0 until reply,
    )
    assertTrue(
      "the queued render was dispatched: $order",
      order[reply].result().toString().contains("preview-A"),
    )
    val readyParams = order[ready]["params"]!!.jsonObject
    assertEquals(1, readyParams["readySlots"]?.jsonPrimitive?.intOrNull)
    assertEquals(1, readyParams["totalSlots"]?.jsonPrimitive?.intOrNull)
    daemon.stop()
  }

  @Test
  fun `a client that does not opt in is answered only once the host is up`() {
    val daemon = Daemon(SlowStartHost(gate))
    val client = daemon.client

    client.send(1, "initialize", initializeParams(bootProgress = false))
    assertNull("initialize must wait for the host", client.poll(500) { it.id() == 1 })
    gate.countDown()
    val init = client.collectUntil { it.id() == 1 }.last()
    assertNull("no booting field on the wire", init.result()?.get("booting"))
    client.notify("initialized")
    client.request(2, "renderNow", """{"previews":["preview-A"],"tier":"fast"}""")
    val all = client.collectUntil { it.method() == "renderFinished" }
    assertTrue("no bootProgress for a client that did not ask: $all", all.none { it.isBoot() })
    daemon.stop()
  }

  @Test
  fun `an opted-in client whose host is already up gets the old answer`() {
    gate.countDown()
    val daemon = Daemon(SlowStartHost(gate))
    // Let the host finish before the client writes anything.
    Thread.sleep(300)
    val init = daemon.client.request(1, "initialize", initializeParams(bootProgress = true))
    assertNull(init.result()?.get("booting"))
    daemon.client.notify("initialized")
    assertNull(daemon.client.poll(300) { it.isBoot() })
    daemon.stop()
  }

  @Test
  fun `a failed boot is reported, then ends the server as before`() {
    val daemon = Daemon(SlowStartHost(gate, failure = IllegalStateException("no android-all")))
    val init = daemon.client.request(1, "initialize", initializeParams(bootProgress = true))
    assertEquals(true, init.result()?.get("booting")?.jsonPrimitive?.booleanOrNull)
    gate.countDown()
    val failed = daemon.client.awaitBoot(BootPhase.FAILED)
    assertTrue(
      "$failed",
      failed["params"]!!.jsonObject["message"].toString().contains("no android-all"),
    )
    daemon.serverThread.join(10_000)
    assertTrue(
      "run() rethrows what host.start() threw",
      daemon.runFailure.get() is IllegalStateException,
    )
  }

  @Test
  fun `the socket front end answers a later client by its own opt-in`() {
    val socket = dir.resolve("d.sock")
    val frontEnd = DaemonSocketFrontEnd(socket, 60_000L, log = {})
    frontEnd.start()
    val server =
      JsonRpcServer(
        input = frontEnd.serverInput,
        output = frontEnd.serverOutput,
        host = SlowStartHost(gate),
        daemonVersion = "test",
        onExit = {},
      )
    Thread({ runCatching { server.run() } }, "early-front-end-server")
      .apply { isDaemon = true }
      .start()

    val first =
      Client(
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
          connect(UnixDomainSocketAddress.of(socket))
        }
      )
    val firstInit = first.request(1, "initialize", initializeParams(bootProgress = true))
    assertEquals(true, firstInit.result()?.get("booting")?.jsonPrimitive?.booleanOrNull)
    first.notify("initialized")
    first.awaitBoot(BootPhase.STARTING)
    first.close()

    // Same session options but no opt-in: not refused as "different options", and not answered
    // until the host is up.
    val second =
      Client(
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
          connect(UnixDomainSocketAddress.of(socket))
        }
      )
    second.send(1, "initialize", initializeParams(bootProgress = false))
    assertNull("a client that did not opt in waits", second.poll(500) { it.id() == 1 })
    gate.countDown()
    val secondInit = second.collectUntil { it.id() == 1 }
    assertNull("no error: ${secondInit.last()}", secondInit.last()["error"])
    assertNull(secondInit.last().result()?.get("booting"))
    assertTrue("$secondInit", secondInit.none { it.isBoot() })
    second.close()
  }

  @Test
  fun `a later client waiting on the socket front end gets an error when the boot fails`() {
    val socket = dir.resolve("f.sock")
    val frontEnd = DaemonSocketFrontEnd(socket, 60_000L, log = {})
    frontEnd.start()
    val server =
      JsonRpcServer(
        input = frontEnd.serverInput,
        output = frontEnd.serverOutput,
        host = SlowStartHost(gate, failure = IllegalStateException("no android-all")),
        daemonVersion = "test",
        onExit = {},
      )
    Thread({ runCatching { server.run() } }, "early-front-end-failing-server")
      .apply { isDaemon = true }
      .start()

    val first = Client(socketChannel(socket))
    val firstInit = first.request(1, "initialize", initializeParams(bootProgress = true))
    assertEquals(true, firstInit.result()?.get("booting")?.jsonPrimitive?.booleanOrNull)
    first.close()

    val second = Client(socketChannel(socket))
    second.send(1, "initialize", initializeParams(bootProgress = false))
    assertNull("a client that did not opt in waits", second.poll(500) { it.id() == 1 })
    gate.countDown()
    val reply = second.collectUntil { it.id() == 1 }.last()
    assertNull("a failed boot is not a successful handshake: $reply", reply["result"])
    assertTrue("$reply", reply["error"].toString().contains("no android-all"))
    second.close()
  }

  private fun socketChannel(socket: Path): SocketChannel =
    SocketChannel.open(StandardProtocolFamily.UNIX).apply {
      connect(UnixDomainSocketAddress.of(socket))
    }

  /**
   * A host whose start() blocks on [gate], reporting slot 0 like a one-sandbox Robolectric host.
   */
  private class SlowStartHost(
    private val gate: CountDownLatch,
    private val failure: Throwable? = null,
    private val delegate: FakeRenderHost = FakeRenderHost(),
  ) : RenderHost by delegate {
    @Volatile private var listener: BootProgressListener? = null

    override fun setBootProgressListener(listener: BootProgressListener?) {
      this.listener = listener
    }

    override fun start() {
      listener?.onBootProgress(BootPhase.SANDBOX_BOOTING, 0, 0, 1)
      gate.await()
      failure?.let { throw it }
      delegate.start()
      listener?.onBootProgress(BootPhase.SANDBOX_READY, 0, 1, 1)
    }
  }

  /** A [JsonRpcServer] over in-memory pipes, running on its own thread. */
  private class Daemon(host: RenderHost) {
    private val toServer = PipedOutputStream()
    private val serverIn = PipedInputStream(toServer, 1 shl 16)
    private val fromServer = PipedInputStream(1 shl 16)
    private val serverOut = PipedOutputStream(fromServer)
    val runFailure = AtomicReference<Throwable?>()
    val client = Client(fromServer, toServer)
    val serverThread =
      Thread(
          {
            try {
              JsonRpcServer(
                  input = serverIn,
                  output = serverOut,
                  host = host,
                  daemonVersion = "test",
                  idleTimeoutMs = 100,
                  onExit = {},
                )
                .run()
            } catch (t: Throwable) {
              runFailure.set(t)
            }
          },
          "early-init-server",
        )
        .apply {
          isDaemon = true
          start()
        }

    fun stop() {
      client.request(99, "shutdown")
      client.notify("exit")
    }
  }

  private class Client(input: InputStream, private val output: OutputStream) {
    constructor(channel: SocketChannel) : this(channel.inputStream(), channel.outputStream()) {
      this.channel = channel
    }

    private var channel: SocketChannel? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val received = LinkedBlockingQueue<JsonObject>()

    init {
      val framer = ContentLengthFramer(input)
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
          "early-init-client",
        )
        .apply {
          isDaemon = true
          start()
        }
    }

    fun request(id: Int, method: String, params: String = "{}"): JsonObject {
      send(id, method, params)
      return collectUntil { it.id() == id }.last()
    }

    fun send(id: Int, method: String, params: String = "{}") =
      write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    fun notify(method: String) = write("""{"jsonrpc":"2.0","method":"$method","params":{}}""")

    fun awaitBoot(phase: String): JsonObject = collectUntil { it.bootPhase() == phase }.last()

    /** The first message within [ms] matching [match], or null; others seen are dropped. */
    fun poll(ms: Long, match: (JsonObject) -> Boolean): JsonObject? {
      val deadline = System.currentTimeMillis() + ms
      while (System.currentTimeMillis() < deadline) {
        val next = received.poll(50, TimeUnit.MILLISECONDS) ?: continue
        if (match(next)) return next
        stash += next
      }
      return null
    }

    private val stash = ArrayDeque<JsonObject>()

    /** Every message up to and including the first [last] matches, in arrival order. */
    fun collectUntil(last: (JsonObject) -> Boolean): List<JsonObject> {
      val seen = mutableListOf<JsonObject>()
      while (stash.isNotEmpty()) {
        val next = stash.removeFirst()
        seen += next
        if (last(next)) return seen
      }
      val deadline = System.currentTimeMillis() + 20_000
      while (System.currentTimeMillis() < deadline) {
        val next = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
        seen += next
        if (last(next)) return seen
      }
      throw AssertionError("message never arrived; saw $seen")
    }

    fun close() {
      channel?.close()
    }

    private fun write(text: String) {
      val payload = text.encodeToByteArray()
      synchronized(output) {
        output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
      }
    }
  }

  private companion object {
    fun initializeParams(bootProgress: Boolean): String =
      """{"protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp","moduleId":":test",""" +
        """"moduleProjectDir":"/tmp","capabilities":{"visibility":true,"metrics":false""" +
        (if (bootProgress) ""","bootProgress":true}}""" else "}}")

    fun JsonObject.id(): Int? = get("id")?.jsonPrimitive?.intOrNull

    fun JsonObject.method(): String? = get("method")?.jsonPrimitive?.contentOrNull

    fun JsonObject.result(): JsonObject? = get("result") as? JsonObject

    fun JsonObject.isBoot(): Boolean = method() == BootPhase.METHOD

    fun JsonObject.bootPhase(): String? =
      if (isBoot()) get("params")?.jsonObject?.get("phase")?.jsonPrimitive?.contentOrNull else null
  }
}
