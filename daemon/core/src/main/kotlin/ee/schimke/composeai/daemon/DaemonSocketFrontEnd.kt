package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lets one daemon serve successive clients over a Unix domain socket, keeping its host and warm
 * sandboxes between them (compose-ai-tools#6104).
 *
 * [JsonRpcServer] serves exactly one client for the life of the process. Rather than teach it
 * several, this front end *is* that one client: the server reads [serverInput] and writes
 * [serverOutput], and the front end relays one socket connection at a time onto them.
 * - The first client's `initialize` reaches the server; its result is cached and answered locally
 *   to every later client, whose `initialized` is dropped.
 * - A client's `shutdown` is answered once the renders it queued have finished, and `exit` ends
 *   only its connection. A client that drops without `shutdown` is drained the same way before the
 *   next is accepted, so no client sees another's notifications.
 * - A later client whose `initialize` options differ from the first's is refused: they are
 *   session-wide in the server, and silently ignoring them would drop what the client asked for.
 * - Held sessions (`interactive/`, `stream/`, `recording/`, `xr/`) are refused; they outlive a
 *   request and belong to a stdio daemon with one owner.
 * - Request ids are rewritten so two clients that both start at `1` never collide; a response is
 *   routed back to the connection that asked, under its own id, and dropped if that client left.
 * - Notifications go to whichever client is connected.
 * - [STOP_METHOD] from a client, or [keepAliveIdleMs] with none connected, sends the server a real
 *   `shutdown` and then `exit`, which ends the process.
 *
 * Connections are served one at a time: a second client waits in the accept backlog until the first
 * disconnects, which keeps two CLI invocations from interleaving renders.
 */
public class DaemonSocketFrontEnd(
  private val socketPath: Path,
  private val keepAliveIdleMs: Long,
  private val log: (String) -> Unit = System.err::println,
) {

  private val toServer = ByteQueue()
  private val fromServer = ByteQueue()

  /** What [JsonRpcServer] reads. */
  public val serverInput: InputStream
    get() = toServer.input

  /** What [JsonRpcServer] writes. */
  public val serverOutput: OutputStream
    get() = fromServer.output

  private val nextServerId = AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, Pending>()
  private val stopping = AtomicBoolean(false)
  private var serverChannel: ServerSocketChannel? = null

  @Volatile private var current: Connection? = null
  @Volatile private var lastDisconnectNs: Long = System.nanoTime()
  @Volatile private var initializedForwarded = false

  /** Guards [initializeResult], [initializeSent] and [initializeWaiters]. */
  private val initializeLock = Any()
  private var initializeResult: JsonElement? = null
  private var initializeSent = false
  private val initializeWaiters = mutableListOf<Pair<Connection, JsonElement>>()
  private var initializeParams: JsonElement? = null

  /** Preview ids a `renderNow` queued that have not finished; guarded by its own monitor. */
  private val inFlight = HashSet<String>()

  /** Ids that finished before their `renderNow` response was relayed. */
  private val finishedEarly = HashSet<String>()

  /** `renderNow` requests forwarded but not yet answered; guarded by [inFlight]'s monitor. */
  private var renderNowsUnanswered = 0

  /** A forwarded request awaiting the server's response; [connection] is null for our own. */
  private class Pending(
    val connection: Connection?,
    val clientId: JsonElement,
    val initialize: Boolean = false,
    val renderNow: Boolean = false,
    val onResponse: (() -> Unit)? = null,
  )

  /** Binds the socket and starts relaying. Call before [JsonRpcServer.run]. */
  public fun start() {
    Files.createDirectories(socketPath.toAbsolutePath().parent)
    // A socket file left by a daemon that died without cleanup refuses the bind.
    Files.deleteIfExists(socketPath)
    val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    channel.bind(UnixDomainSocketAddress.of(socketPath))
    serverChannel = channel
    // However the process ends (exit, classpathDirty, a signal), the next client must not find a
    // socket file nothing listens on.
    Runtime.getRuntime()
      .addShutdownHook(Thread { runCatching { Files.deleteIfExists(socketPath) } })
    log("compose-ai-daemon: serving clients on $socketPath (keepAliveIdleMs=$keepAliveIdleMs)")
    daemonThread("compose-ai-daemon-socket-accept") { acceptLoop(channel) }
    daemonThread("compose-ai-daemon-socket-relay") { serverReaderLoop() }
    daemonThread("compose-ai-daemon-socket-idle") { idleLoop() }
  }

  private fun acceptLoop(channel: ServerSocketChannel) {
    while (!stopping.get()) {
      val socket =
        try {
          channel.accept()
        } catch (_: IOException) {
          break
        }
      val connection = Connection(socket)
      current = connection
      try {
        clientReaderLoop(connection)
      } finally {
        current = null
        // A client that dropped mid-render: finish its renders before the next client connects,
        // so their notifications go nowhere rather than to someone else.
        awaitRenders()
        lastDisconnectNs = System.nanoTime()
        // The server answers every forwarded request; an initialize or renderNow reply still
        // matters once its client has gone (the cached handshake, the drain), so only others go.
        pending.entries.removeIf {
          it.value.connection === connection && !it.value.initialize && !it.value.renderNow
        }
        connection.close()
      }
    }
  }

  private fun clientReaderLoop(connection: Connection) {
    val framer = ContentLengthFramer(connection.input)
    while (!stopping.get()) {
      val bytes =
        try {
          framer.readFrame() ?: return
        } catch (_: IOException) {
          return
        }
      val obj = parse(bytes) ?: continue
      val method = obj["method"]?.jsonPrimitive?.contentOrNull
      val id = obj["id"]?.takeIf { it !is JsonNull }
      when {
        method == "initialize" && id != null -> initialize(connection, obj, id)
        method == "initialized" && id == null -> {
          if (!initializedForwarded) {
            initializedForwarded = true
            toServer.write(frame(obj))
          }
        }
        method == "shutdown" && id != null -> {
          awaitRenders()
          connection.send(response(id, JsonNull))
        }
        method == "exit" && id == null -> return
        method == STOP_METHOD -> {
          if (id != null) connection.send(response(id, JsonNull))
          stopServer("stop requested")
          return
        }
        method != null && HELD_SESSION_PREFIXES.any(method::startsWith) -> {
          if (id != null) {
            connection.send(
              error(id, ERR_METHOD_NOT_FOUND, "$method is not served over the daemon socket")
            )
          }
        }
        method != null && id != null ->
          forward(Pending(connection, id, renderNow = method == "renderNow"), obj)
        method != null -> toServer.write(frame(obj))
        // A response to a server request: the server sends none, so there is nothing to route.
        else -> Unit
      }
    }
  }

  /** Forwards the first `initialize`; answers every other from its result, waiting if need be. */
  private fun initialize(connection: Connection, obj: JsonObject, id: JsonElement) {
    val params = sessionOptions(obj["params"])
    val answer: JsonElement?
    synchronized(initializeLock) {
      if (initializeSent && params != initializeParams) {
        connection.send(
          error(
            id,
            ERR_INVALID_PARAMS,
            "this daemon was initialized with different options; start a separate one",
          )
        )
        return
      }
      answer = initializeResult
      when {
        answer != null -> Unit
        initializeSent -> initializeWaiters += connection to id
        else -> {
          initializeSent = true
          initializeParams = params
          forward(Pending(connection, id, initialize = true), obj)
        }
      }
    }
    if (answer != null) connection.send(response(id, answer))
  }

  /** Blocks until every render a `renderNow` queued has sent `renderFinished` or `renderFailed`. */
  private fun awaitRenders() {
    val deadline = System.nanoTime() + RENDER_DRAIN_MS * 1_000_000L
    synchronized(inFlight) {
      while ((inFlight.isNotEmpty() || renderNowsUnanswered > 0) && !stopping.get()) {
        val leftMs = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) {
          log("compose-ai-daemon: socket front end gave up waiting for $inFlight")
          inFlight.clear()
          renderNowsUnanswered = 0
          return
        }
        (inFlight as Object).wait(minOf(leftMs, IDLE_POLL_MS))
      }
    }
  }

  /** Records what a `renderNow` reply queued; an error reply queued nothing. */
  private fun trackQueued(result: JsonElement?) {
    val queued =
      (result as? JsonObject)
        ?.get("queued")
        ?.let { it as? JsonArray }
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    synchronized(inFlight) {
      renderNowsUnanswered = (renderNowsUnanswered - 1).coerceAtLeast(0)
      queued.forEach { if (!finishedEarly.remove(it)) inFlight += it }
      (inFlight as Object).notifyAll()
    }
  }

  private fun trackFinished(obj: JsonObject) {
    val method = obj["method"]?.jsonPrimitive?.contentOrNull
    if (method != "renderFinished" && method != "renderFailed") return
    val id = (obj["params"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    synchronized(inFlight) {
      if (!inFlight.remove(id)) finishedEarly += id
      (inFlight as Object).notifyAll()
    }
  }

  private fun forward(target: Pending, obj: JsonObject) {
    if (target.renderNow) synchronized(inFlight) { renderNowsUnanswered++ }
    val serverId = nextServerId.getAndIncrement()
    pending[serverId] = target
    toServer.write(frame(JsonObject(obj + ("id" to JsonPrimitive(serverId)))))
  }

  private fun serverReaderLoop() {
    val framer = ContentLengthFramer(fromServer.input)
    try {
      while (true) {
        val bytes = framer.readFrame() ?: break
        val obj = parse(bytes) ?: continue
        val serverId = obj["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
        if (obj["method"] == null && serverId != null) {
          val target = pending.remove(serverId) ?: continue
          target.onResponse?.invoke()
          if (target.renderNow) trackQueued(obj["result"])
          target.connection?.send(frame(JsonObject(obj + ("id" to target.clientId))))
          if (target.initialize) obj["result"]?.let(::publishInitializeResult)
        } else {
          trackFinished(obj)
          current?.send(bytes.withHeader())
        }
      }
    } catch (_: IOException) {
      // The server closed its output: it is exiting.
    } finally {
      close()
    }
  }

  private fun publishInitializeResult(result: JsonElement) {
    val waiters =
      synchronized(initializeLock) {
        initializeResult = result
        initializeWaiters.toList().also { initializeWaiters.clear() }
      }
    waiters.forEach { (connection, id) -> connection.send(response(id, result)) }
  }

  private fun idleLoop() {
    while (!stopping.get()) {
      try {
        Thread.sleep(minOf(keepAliveIdleMs, IDLE_POLL_MS))
      } catch (_: InterruptedException) {
        return
      }
      val idleMs = (System.nanoTime() - lastDisconnectNs) / 1_000_000L
      if (current == null && idleMs >= keepAliveIdleMs) stopServer("idle for ${idleMs}ms")
    }
  }

  /** Sends the server `shutdown`, then `exit` once it has drained; the server ends the process. */
  private fun stopServer(reason: String) {
    if (!stopping.compareAndSet(false, true)) return
    log("compose-ai-daemon: socket front end stopping ($reason)")
    runCatching { serverChannel?.close() }
    runCatching { Files.deleteIfExists(socketPath) }
    // An `exit` the server reads before `shutdown` has drained is an unclean exit.
    forward(
      Pending(connection = null, clientId = JsonNull) { toServer.write(frame(message("exit"))) },
      message("shutdown"),
    )
  }

  private fun close() {
    stopping.set(true)
    runCatching { serverChannel?.close() }
    runCatching { Files.deleteIfExists(socketPath) }
    current?.close()
    toServer.close()
  }

  private class Connection(private val socket: SocketChannel) {
    val input: InputStream = socket.inputStream()
    private val output: OutputStream = socket.outputStream()

    fun send(bytes: ByteArray) {
      synchronized(this) {
        try {
          output.write(bytes)
        } catch (_: IOException) {
          // The client left; its reader loop sees the same and ends the connection.
        }
      }
    }

    fun close() {
      runCatching { socket.close() }
    }
  }

  /**
   * A blocking byte pipe. `java.io.Piped*Stream` fails a read with "write end dead" once the last
   * thread that wrote has exited, and here every client connection writes from its own thread.
   */
  private class ByteQueue {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    fun write(bytes: ByteArray) {
      if (!closed.get()) chunks.put(bytes)
    }

    fun close() {
      if (closed.compareAndSet(false, true)) chunks.put(EOF)
    }

    val output: OutputStream =
      object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
          if (len > 0) this@ByteQueue.write(b.copyOfRange(off, off + len))
        }

        override fun close() = this@ByteQueue.close()
      }

    val input: InputStream =
      object : InputStream() {
        private var chunk: ByteArray = ByteArray(0)
        private var pos = 0

        private fun fill(): Boolean {
          while (pos >= chunk.size) {
            if (chunk === EOF) return false
            chunk = chunks.take()
            pos = 0
          }
          return true
        }

        override fun read(): Int = if (!fill()) -1 else chunk[pos++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (len == 0) return 0
          if (!fill()) return -1
          val n = minOf(len, chunk.size - pos)
          System.arraycopy(chunk, pos, b, off, n)
          pos += n
          return n
        }

        // A SIGTERM hook closes the server's input to end its read loop, as stdin EOF would.
        override fun close() = this@ByteQueue.close()
      }

    private companion object {
      val EOF = ByteArray(0)
    }
  }

  public companion object {
    /** Front-end-only request: stop the daemon now rather than after [keepAliveIdleMs]. */
    public const val STOP_METHOD: String = "daemon/stop"

    private const val IDLE_POLL_MS = 1_000L

    /** The longest a client's `shutdown`, or the next client, waits for queued renders. */
    private const val RENDER_DRAIN_MS = 10 * 60_000L

    private const val ERR_METHOD_NOT_FOUND = -32601
    private const val ERR_INVALID_PARAMS = -32602

    private val HELD_SESSION_PREFIXES = listOf("interactive/", "stream/", "recording/", "xr/")

    /** `initialize` params minus the fields that only describe the client. */
    private fun sessionOptions(params: JsonElement?): JsonElement? =
      (params as? JsonObject)?.let { JsonObject(it - "clientVersion") } ?: params

    private fun error(id: JsonElement, code: Int, message: String): ByteArray =
      frame(
        JsonObject(
          mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to id,
            "error" to
              JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))),
          )
        )
      )

    /** A front end when [DaemonProperties.socketPath] is set, else `null` (serve stdio). */
    public fun fromSystemProperties(): DaemonSocketFrontEnd? =
      DaemonProperties.socketPath.read()?.let {
        DaemonSocketFrontEnd(Path.of(it), DaemonProperties.keepAliveIdleMs.read())
      }

    private fun parse(bytes: ByteArray): JsonObject? = runCatching {
      Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
    }
      .getOrNull()

    private fun message(method: String): JsonObject =
      JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "method" to JsonPrimitive(method)))

    private fun response(id: JsonElement, result: JsonElement): ByteArray =
      frame(JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to result)))

    private fun frame(obj: JsonObject): ByteArray = obj.toString().encodeToByteArray().withHeader()

    private fun ByteArray.withHeader(): ByteArray {
      val out = ByteArrayOutputStream(size + 32)
      out.write("Content-Length: $size\r\n\r\n".toByteArray(Charsets.US_ASCII))
      out.write(this)
      return out.toByteArray()
    }

    /**
     * Stream views that read and write the channel directly. `Channels.newInputStream` and
     * `newOutputStream` share the channel's blocking lock, so a read waiting on the client would
     * block every write to it.
     */
    internal fun SocketChannel.inputStream(): InputStream =
      object : InputStream() {
        override fun read(): Int {
          val one = ByteArray(1)
          return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
          if (len == 0) 0 else this@inputStream.read(ByteBuffer.wrap(b, off, len))
      }

    internal fun SocketChannel.outputStream(): OutputStream =
      object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
          val buffer = ByteBuffer.wrap(b, off, len)
          while (buffer.hasRemaining()) this@outputStream.write(buffer)
        }
      }

    private fun daemonThread(name: String, body: () -> Unit) {
      Thread(body, name).apply { isDaemon = true }.start()
    }
  }
}
