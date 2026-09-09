package ee.schimke.composeai.daemon

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the wire-side leg of the app-declared theme axis (`@ThemeCatalog` /
 * `@WearThemeCatalog`).
 *
 * [JsonRpcServer.renderTargetFor] serializes the render-affecting overrides that have no typed wire
 * token of their own into a single base64 `overrides=<bag>` token. `themeProvider` was missing from
 * that bag, so a one-shot `renderNow.overrides.themeProvider = <providerFqn>` was dropped on the
 * wire: the renderer read `spec.overrides?.themeProvider == null` in `InvokeWithOptionalWrapper`
 * and fell back to the preview's declared `@PreviewWrapper`. On the preview server that surfaced as
 * a Theme picker whose chips redrew byte-identical (unthemed) pixels — every declared theme
 * rendered the same. The live `stream/start` path was unaffected: it carries the FQN separately as
 * `InteractiveCommand.Start.themeProviderFqn`.
 *
 * Drives a full JSON-RPC `initialize` → `renderNow` round-trip against a payload-capturing host and
 * asserts the encoded payload carries the FQN inside the `overrides=<base64>` token. Sibling to
 * [PermissionsOverrideEncodingTest] — same plumbing, different override field.
 */
class ThemeProviderOverrideEncodingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun themeProviderOverrideIsEncodedIntoTheExtensionBag() {
    val captured =
      renderAndCaptureTarget(
        overrides = """{"themeProvider":"com.example.designcatalogwearm3.WearTealThemeCatalog"}"""
      )
    val bag = requireNotNull(captured.overrides)
    assertEquals("com.example.designcatalogwearm3.WearTealThemeCatalog", bag.themeProvider)
  }

  @Test(timeout = 30_000)
  fun themeProviderRidesAlongsideTheOtherBagFields() {
    // The theme selection travels in the same single bag as the planner-driven fields rather than
    // sprouting a token of its own.
    val captured =
      renderAndCaptureTarget(
        overrides =
          """{
                "themeProvider":"com.example.ThemeCatalog",
                "material3Theme":{"sourceColor":"#FF3366FF"}
              }"""
      )
    val bag = requireNotNull(captured.overrides)
    assertEquals("com.example.ThemeCatalog", bag.themeProvider)
    assertNotNull("material3Theme must round-trip", bag.material3Theme)
  }

  @Test(timeout = 30_000)
  fun aBlankThemeProviderArrivesBlankRatherThanAsASelection() {
    // A blank FQN is "no theme selected". It used to be load-bearing that it did not *force* an
    // `overrides=` bag into the payload; with the object travelling whole, what matters is only
    // that the renderer can still tell it apart from a real selection.
    val captured = renderAndCaptureTarget(overrides = """{"themeProvider":"","uiMode":"dark"}""")
    assertTrue(
      "a blank themeProvider must not read as a selection",
      captured.overrides?.themeProvider.isNullOrBlank(),
    )
  }

  /**
   * Spins up a [JsonRpcServer] backed by a payload-capturing host with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received. Mirrors [PermissionsOverrideEncodingTest]'s
   * helper verbatim — kept file-local for the same reason.
   */
  private fun renderAndCaptureTarget(overrides: String): RenderTarget.Preview {
    val sourceKt = java.nio.file.Files.createTempFile("theme-provider-override-test", ".kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview fun A() {}\n")
    val previewDto =
      PreviewInfoDto(
        id = "preview-A",
        className = "com.example.AKt",
        methodName = "A",
        sourceFile = sourceKt.toAbsolutePath().toString(),
      )
    val index = PreviewIndex.fromMap(path = sourceKt, byId = mapOf("preview-A" to previewDto))

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = PayloadCapturingThemeProviderHost()
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "theme-provider-override-encoding-test").apply { isDaemon = true }
    serverThread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
              received.put(obj)
            }
          } catch (_: Throwable) {}
        },
        "theme-provider-override-encoding-test-reader",
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
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast","overrides":$overrides}}""",
      )
      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished must arrive within timeout", finished)

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
      return (host.lastTarget.get() as? RenderTarget.Preview)
        ?: error("host never received a Preview render target")
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      try {
        java.nio.file.Files.deleteIfExists(sourceKt)
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  private fun writeFrame(out: PipedOutputStream, jsonStr: String) {
    val payload = jsonStr.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 10_000,
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

/**
 * Captures the [RenderRequest.Render.payload] string the daemon submits, then completes the render
 * synchronously with a stub success result. File-local copy of the shape
 * [PermissionsOverrideEncodingTest] uses, so the two tests take no cross-file dependency.
 */
private class PayloadCapturingThemeProviderHost : RenderHost {
  val lastTarget: AtomicReference<RenderTarget?> = AtomicReference(null)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()

  @Volatile private var stopped = false

  private val worker: Thread =
    Thread(
        {
          while (!stopped) {
            when (val req = queue.poll(50, TimeUnit.MILLISECONDS)) {
              null -> continue
              is RenderRequest.Render -> {
                lastTarget.set(req.target)
                results.put(
                  RenderResult(
                    id = req.id,
                    classLoaderHashCode = 0,
                    classLoaderName = "theme-provider-override-encoding-test",
                  )
                )
              }
              // Never enqueued here: `submit` only accepts a Render. Present so the `when` stays
              // exhaustive over `RenderRequest` (issue #3749 added ParameterRows).
              is RenderRequest.ParameterRows -> {}
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-theme-provider-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingThemeProviderHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
