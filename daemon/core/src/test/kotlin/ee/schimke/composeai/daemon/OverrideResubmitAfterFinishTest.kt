package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistoryFilter
import ee.schimke.composeai.daemon.history.HistoryListPage
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryReadResult
import ee.schimke.composeai.daemon.history.HistorySource
import ee.schimke.composeai.daemon.history.WriteResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coalescing contract (PROTOCOL.md § 5, `renderNow.overrides`) tells a client whose
 * override-bearing `renderNow` was rejected with `coalesced` to resubmit on the next
 * `renderFinished`. The daemon used to send `renderFinished` and only clear the in-flight flag
 * after recording history, so a resubmit that arrived in between was rejected again, and no further
 * `renderFinished` came to retry on (yschimke/compose-ag-plugin#64). Such a request now runs after
 * the finishing render instead.
 */
class OverrideResubmitAfterFinishTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun `an override render resubmitted on renderFinished is queued, not coalesced`() {
    val tmp = Files.createTempDirectory("override-resubmit").toFile()
    val png = File(tmp, "preview-A.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2)) }
    val history = GatedHistorySource()
    val host = CountingHost(png)

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
        historyManager = HistoryManager(listOf(history), module = ":t", gitProvenance = null),
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "override-resubmit-server").apply { isDaemon = true }
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
        "override-resubmit-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      fun renderNow(id: Int, uiMode: String) =
        writeFrame(
          clientToServerOut,
          """{"jsonrpc":"2.0","id":$id,"method":"renderNow","params":{
                "previews":["preview-A"],"tier":"fast","overrides":{"uiMode":"$uiMode"}}}""",
        )

      // pollUntil drops what it skips, so wait for the notification itself, not the response.
      renderNow(2, "light")
      assertNotNull(
        "first renderFinished",
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" },
      )
      // The first render is now recording history, held open by the gate: the window in which a
      // client resubmitting on renderFinished used to be rejected.
      assertTrue(history.firstWriteStarted.await(5, TimeUnit.SECONDS))

      renderNow(3, "dark")
      val response = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      val result = response!!["result"]!!.jsonObject
      assertEquals(
        listOf("preview-A"),
        result["queued"]!!.jsonArray.map { it.jsonPrimitive.content },
      )
      assertTrue("no rejection: $result", result["rejected"]?.jsonArray.isNullOrEmpty())
      assertEquals("the resubmit waits for the first render to finish", 1, host.renders.get())

      history.release()
      assertNotNull(
        "the resubmitted render runs and finishes",
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" },
      )
      assertEquals(2, host.renders.get())

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      history.release()
      runCatching { clientToServerOut.close() }
      runCatching { serverToClientIn.close() }
      serverThread.join(5_000)
    }
  }

  private fun writeFrame(out: PipedOutputStream, body: String) {
    val payload = body.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
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

private class CountingHost(private val png: File) : RenderHost {
  val renders = AtomicInteger()

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    renders.incrementAndGet()
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "counting",
      artifact = RenderArtifact(png.absolutePath),
    )
  }

  override fun shutdown(timeoutMs: Long) {}
}

/** A writable history source whose first write blocks until [release]. */
private class GatedHistorySource : HistorySource {
  val firstWriteStarted = CountDownLatch(1)
  private val gate = CountDownLatch(1)

  fun release() = gate.countDown()

  override val id = "gated"
  override val kind = "fs"

  override fun supportsWrites() = true

  override fun write(entry: HistoryEntry, png: ByteArray): WriteResult {
    if (firstWriteStarted.count > 0) {
      firstWriteStarted.countDown()
      gate.await(10, TimeUnit.SECONDS)
    }
    return WriteResult.WRITTEN
  }

  override fun list(filter: HistoryFilter) = HistoryListPage(entries = emptyList(), totalCount = 0)

  override fun read(entryId: String, includeBytes: Boolean): HistoryReadResult? = null
}
