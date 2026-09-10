package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.BackendKind
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test

/**
 * The loop, driven against a fake daemon on the far end of a real pipe rather than against a mock
 * of the client. What is being tested is ordering — handlers before the first frame, shutdown
 * before reporting a failed handshake, death told apart from a close we asked for — and none of
 * that is observable if the transport is stubbed out.
 */
class ManagedDaemonTest {

  private val daemons = CopyOnWriteArrayList<FakeDaemon>()

  @After
  fun tearDown() {
    daemons.forEach { it.stop() }
  }

  @Test
  fun `start spawns, handshakes and reaches READY`() {
    val listener = RecordingListener()
    val managed = managed(listener = listener)

    assertThat(managed.state).isEqualTo(ManagedDaemon.State.NEW)
    val result = managed.start(workspaceRoot = "/ws")

    assertThat(result.daemonVersion).isEqualTo("fake-1")
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.READY)
    assertThat(managed.initializeResult).isSameInstanceAs(result)
    assertThat(listener.transitions)
      .containsExactly(
        ManagedDaemon.State.NEW to ManagedDaemon.State.STARTING,
        ManagedDaemon.State.STARTING to ManagedDaemon.State.READY,
      )
      .inOrder()
  }

  /**
   * The ordering constraint `DaemonSpawn.client` documents: a notification the daemon emits
   * *during* the handshake is read by the transport's reader thread, so a handler wired after
   * `start` returns would never see it. Making the listener a constructor parameter is what removes
   * the mistake — this test is what proves it stayed removed.
   */
  @Test
  fun `a notification sent during the handshake still reaches the listener`() {
    val listener = RecordingListener()
    val managed = managed(listener = listener, notifyDuringHandshake = true)

    managed.start(workspaceRoot = "/ws")

    listener.awaitNotifications(1)
    assertThat(listener.notifications.map { it.first }).contains("discoveryUpdated")
  }

  @Test
  fun `a spawn failure never reaches STARTING and reports the module`() {
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> throw IllegalStateException("no java on PATH") },
      )

    val thrown = runCatching { managed.start(workspaceRoot = "/ws") }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(DaemonStartException::class.java)
    assertThat(thrown).hasMessageThat().contains(":app")
    assertThat(thrown).hasMessageThat().contains("no java on PATH")
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
  }

  /**
   * A handshake that fails must not leave the JVM running. On the Android backend that JVM holds a
   * Robolectric sandbox, so a leaked one is real memory nobody can reclaim.
   */
  @Test
  fun `a failed handshake shuts the process down before reporting`() {
    val daemon = FakeDaemon(answerInitialize = false)
    daemons += daemon
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
      )

    val thrown = runCatching {
      managed.start(workspaceRoot = "/ws", timeout = 200.milliseconds)
    }
      .exceptionOrNull()

    assertThat(thrown).isInstanceOf(DaemonStartException::class.java)
    assertThat(thrown).hasMessageThat().contains("initialize handshake failed")
    assertThat(daemon.shutdownCalls).isEqualTo(1)
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
  }

  @Test
  fun `a daemon that exits on its own is reported as DIED, once`() {
    val listener = RecordingListener()
    val daemon = FakeDaemon()
    daemons += daemon
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
        listener = listener,
      )
    managed.start(workspaceRoot = "/ws")

    daemon.stop() // the daemon exits; the client's reader thread sees EOF

    listener.awaitDeath()
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.DIED)
    assertThat(listener.deaths).hasSize(1)
    assertThat(listener.deaths.single().observedIn).isEqualTo(ManagedDaemon.State.READY)
    assertThat(listener.deaths.single().message).contains("exited while running")
    // Never a restart: this type reports and the caller decides.
    assertThat(daemon.spawnCount).isEqualTo(1)
  }

  @Test
  fun `a close we asked for is not a death`() {
    val listener = RecordingListener()
    val managed = managed(listener = listener)
    managed.start(workspaceRoot = "/ws")

    managed.close()

    // The transport reaches EOF on the reader thread a moment after shutdown returns, so a naive
    // "is it dead yet" guard passes here by luck. Give that thread time to get it wrong.
    listener.assertNoDeathWithin(500.milliseconds)
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
    assertThat(listener.deaths).isEmpty()
    assertThat(listener.transitions.last())
      .isEqualTo(ManagedDaemon.State.CLOSING to ManagedDaemon.State.CLOSED)
  }

  /**
   * A spawn whose `shutdown` runs the close callback on the **calling** thread — `DaemonClient
   * .close()` reaching the reader's EOF path inline, which is a legitimate implementation. Kotlin's
   * `synchronized` is reentrant, so the lock alone does not save us here: without the CLOSING guard
   * the daemon we are deliberately closing reports itself dead.
   */
  @Test
  fun `a spawn that closes re-entrantly is still not a death`() {
    val listener = RecordingListener()
    val daemon = FakeDaemon(closeReentrantly = true)
    daemons += daemon
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
        listener = listener,
      )
    managed.start(workspaceRoot = "/ws")

    managed.close()

    assertThat(listener.deaths).isEmpty()
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
  }

  @Test
  fun `close is idempotent and shuts the spawn down exactly once`() {
    val daemon = FakeDaemon()
    daemons += daemon
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
      )
    managed.start(workspaceRoot = "/ws")

    managed.close()
    managed.close()
    managed.shutdown(1.seconds)

    assertThat(daemon.shutdownCalls).isEqualTo(1)
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
  }

  @Test
  fun `closing a dead daemon still reaps it`() {
    val daemon = FakeDaemon()
    daemons += daemon
    val listener = RecordingListener()
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
        listener = listener,
      )
    managed.start(workspaceRoot = "/ws")
    daemon.stop()
    listener.awaitDeath()

    managed.close()

    assertThat(daemon.shutdownCalls).isEqualTo(1)
    assertThat(managed.state).isEqualTo(ManagedDaemon.State.CLOSED)
  }

  @Test
  fun `session is only reachable while READY`() {
    val managed = managed()

    assertThat(runCatching { managed.session }.exceptionOrNull())
      .isInstanceOf(IllegalStateException::class.java)

    managed.start(workspaceRoot = "/ws")
    assertThat(managed.session).isNotNull()

    managed.close()
    assertThat(runCatching { managed.session }.exceptionOrNull())
      .isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun `start refuses a second call`() {
    val managed = managed()
    managed.start(workspaceRoot = "/ws")

    assertThat(runCatching { managed.start(workspaceRoot = "/ws") }.exceptionOrNull())
      .isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun `the handshake carries the descriptor's identity by default`() {
    val daemon = FakeDaemon()
    daemons += daemon
    val managed =
      ManagedDaemon(
        workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
        descriptor = descriptor(),
        factory = { _, _ -> daemon.spawn() },
      )

    managed.start(workspaceRoot = "/ws")

    val params = daemon.initializeParams!!
    assertThat(params["moduleId"]!!.jsonPrimitive.content).isEqualTo(":app")
    assertThat(params["moduleProjectDir"]!!.jsonPrimitive.content).isEqualTo("/work")
    assertThat(params["workspaceRoot"]!!.jsonPrimitive.content).isEqualTo("/ws")
  }

  // ── scaffolding ────────────────────────────────────────────────────────────────────────────

  private fun managed(
    listener: ManagedDaemon.Listener = ManagedDaemon.Listener.NONE,
    notifyDuringHandshake: Boolean = false,
  ): ManagedDaemon {
    val daemon = FakeDaemon(notifyDuringHandshake = notifyDuringHandshake)
    daemons += daemon
    return ManagedDaemon(
      workspaceId = WorkspaceId.derive("ws", java.io.File("/ws")),
      descriptor = descriptor(),
      factory = { _, _ -> daemon.spawn() },
      listener = listener,
    )
  }

  private fun descriptor() =
    DaemonLaunchDescriptor(
      schemaVersion = 2,
      modulePath = ":app",
      variant = "desktop",
      enabled = true,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = listOf("/runtime/daemon.jar"),
      jvmArgs = emptyList(),
      systemProperties = emptyMap(),
      workingDirectory = "/work",
      manifestPath = "",
    )

  private class RecordingListener : ManagedDaemon.Listener {
    val transitions = CopyOnWriteArrayList<Pair<ManagedDaemon.State, ManagedDaemon.State>>()
    val notifications = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
    val deaths = CopyOnWriteArrayList<ManagedDaemon.DaemonDeath>()
    private val died = CountDownLatch(1)
    private val notified = CountDownLatch(1)

    override fun onStateChange(from: ManagedDaemon.State, to: ManagedDaemon.State) {
      transitions += from to to
    }

    override fun onNotification(method: String, params: JsonObject?) {
      notifications += method to params
      notified.countDown()
    }

    override fun onDied(death: ManagedDaemon.DaemonDeath) {
      deaths += death
      died.countDown()
    }

    fun awaitDeath() {
      assertThat(died.await(5, TimeUnit.SECONDS)).isTrue()
    }

    fun assertNoDeathWithin(window: kotlin.time.Duration) {
      assertThat(died.await(window.inWholeMilliseconds, TimeUnit.MILLISECONDS)).isFalse()
    }

    fun awaitNotifications(count: Int) {
      assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(notifications.size).isAtLeast(count)
    }
  }

  /**
   * A daemon on the far end of a real pipe: reads `Content-Length` frames, answers `initialize`,
   * and exits when told to. Small enough to read, real enough that the client's own reader thread,
   * framing and EOF handling are exercised rather than mocked away.
   */
  private class FakeDaemon(
    private val answerInitialize: Boolean = true,
    private val notifyDuringHandshake: Boolean = false,
    private val closeReentrantly: Boolean = false,
  ) {
    private val toDaemon = PipedOutputStream()
    private val fromClient = PipedInputStream(toDaemon, 1 shl 16)
    private val toClient = PipedOutputStream()
    private val fromDaemon = PipedInputStream(toClient, 1 shl 16)

    @Volatile var initializeParams: JsonObject? = null
    @Volatile var spawnCount: Int = 0
    @Volatile var shutdownCalls: Int = 0

    private val thread =
      Thread({ serve() }, "fake-daemon").apply {
        isDaemon = true
        start()
      }

    fun spawn(): DaemonSpawn {
      spawnCount++
      return object : DaemonSpawn {
        private var wired: DaemonClient? = null
        private var closeCallback: () -> Unit = {}

        override val client: DaemonClient
          get() = wired!!

        override fun client(
          onNotification: (String, JsonObject?) -> Unit,
          onClose: () -> Unit,
        ): DaemonClient =
          DaemonClient(
              input = fromDaemon,
              output = toDaemon,
              onNotification = onNotification,
              onClose = onClose,
              threadName = "fake-daemon-client-reader",
            )
            .also {
              wired = it
              closeCallback = onClose
            }

        override fun shutdown() {
          shutdownCalls++
          stop()
          runCatching { wired?.close() }
          // Some transports notice EOF inline rather than on a reader thread.
          if (closeReentrantly) closeCallback()
        }
      }
    }

    fun stop() {
      runCatching { toClient.close() }
      runCatching { fromClient.close() }
      thread.interrupt()
    }

    private fun serve() {
      try {
        while (true) {
          val frame = readFrame(fromClient) ?: return
          val message = Json.parseToJsonElement(frame).jsonObject
          val method = message["method"]?.jsonPrimitive?.content ?: continue
          if (method != "initialize") continue
          initializeParams = message["params"]?.jsonObject
          if (notifyDuringHandshake) {
            writeFrame(
              buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "discoveryUpdated")
                put("params", buildJsonObject { put("added", 0) })
              }
            )
          }
          if (!answerInitialize) continue
          val id = message["id"]!!.jsonPrimitive.long
          writeFrame(
            buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", id)
              put(
                "result",
                Json.encodeToJsonElement(
                  InitializeResult.serializer(),
                  InitializeResult(
                    protocolVersion = 2,
                    daemonVersion = "fake-1",
                    pid = 1234L,
                    capabilities = FAKE_CAPABILITIES,
                    classpathFingerprint = "cafebabe",
                    manifest = Manifest(path = "", previewCount = 0),
                  ),
                ),
              )
            }
          )
        }
      } catch (_: Exception) {
        // The pipe closed: the daemon is done.
      }
    }

    private fun writeFrame(message: JsonObject) {
      val bytes = message.toString().toByteArray()
      synchronized(toClient) {
        toClient.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
        toClient.write(bytes)
        toClient.flush()
      }
    }

    private fun readFrame(input: InputStream): String? {
      var length = -1
      while (true) {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
          length = line.substringAfter(':').trim().toInt()
        }
      }
      if (length < 0) return null
      val body = ByteArray(length)
      var read = 0
      while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) return null
        read += n
      }
      return String(body)
    }

    private fun readLine(input: InputStream): String? {
      val buffer = ByteArrayOutputStream()
      while (true) {
        val b = input.read()
        if (b < 0) return null
        if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
        buffer.write(b)
      }
    }
  }

  private companion object {
    /** The narrowest honest handshake answer: a daemon that advertises nothing. */
    val FAKE_CAPABILITIES: ServerCapabilities =
      ServerCapabilities(
        incrementalDiscovery = false,
        sandboxRecycle = false,
        leakDetection = emptyList(),
        dataProducts = emptyList(),
        dataExtensions = emptyList(),
        previewExtensions = emptyList(),
        interactive = false,
        recording = false,
        xr = false,
        recordingFormats = emptyList(),
        knownDevices = emptyList(),
        supportedOverrides = emptyList(),
        backend = BackendKind.DESKTOP,
        androidSdk = null,
        interactiveControlKinds = emptyList(),
      )
  }
}
