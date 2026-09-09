package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.UiMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for #474 — `renderNow.overrides.device` must be resolved into the wire payload's
 * `widthPx` / `heightPx` / `density` tokens by [JsonRpcServer.encodeRenderPayload]. Pre-fix the
 * production path forwarded `device=id:pixel_5` as an opaque string, leaving downstream
 * `RenderSpec` defaults in place — the documented harness `PreviewManifestRouter` is the only place
 * that resolved the catalog, and production daemons don't run it.
 *
 * Drives a full JSON-RPC `initialize` → `renderNow` round-trip against a [PayloadCapturingHost] and
 * asserts on the payload string the host actually receives. We can't assert on rendered pixels at
 * this layer (no Compose runtime), but a wrong payload here is the proximate cause of the bug — the
 * existing `OverrideIntegrationTest`s prove `widthPx=…` payloads do reach the renderer's spec.
 */
class DeviceOverrideEncodingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun deviceOverrideAloneResolvesToCatalogDimensions() {
    val captured = renderAndCaptureTarget(overrides = """{"device":"id:pixel_5"}""")
    // PROTOCOL.md § 5: `id:pixel_5` is widthDp=393, heightDp=851, density=2.75. So the resolved
    // wire payload must carry widthPx=1080 (393 * 2.75 = 1080.75 → 1080), heightPx=2340 (851 *
    // 2.75 = 2340.25 → 2340), density=2.75. The raw `device=id:pixel_5` token still rides along
    // for the wear-round-crop heuristic on the Android backend.
    assertEquals(1080, captured.overrides?.widthPx)
    assertEquals(2340, captured.overrides?.heightPx)
    assertEquals(2.75f, captured.overrides?.density)
    assertEquals("id:pixel_5", captured.overrides?.device)
  }

  @Test(timeout = 30_000)
  fun explicitWidthPxBeatsDeviceDerivedDimensions() {
    val captured = renderAndCaptureTarget(overrides = """{"device":"id:pixel_5","widthPx":600}""")
    // Explicit `widthPx` wins over the device's derived 1080; height/density still flow from the
    // Pixel 5 catalog so a single field doesn't force the caller to repeat the rest.
    assertEquals(600, captured.overrides?.widthPx)
    assertEquals(2340, captured.overrides?.heightPx)
    assertEquals(2.75f, captured.overrides?.density)
    assertEquals("id:pixel_5", captured.overrides?.device)
  }

  @Test(timeout = 30_000)
  fun unknownDeviceIdFallsBackToCatalogDefault() {
    val captured = renderAndCaptureTarget(overrides = """{"device":"id:nonexistent"}""")
    // `DeviceDimensions.resolve` never throws on unknown ids — it returns the catalog `DEFAULT`
    // (400×800 dp at density=2.625, the same xxhdpi-ish constant Android Studio uses when no
    // device is specified). The override still has *some* effect (vs. the bug where it had
    // none); callers who want strict behaviour go through MCP's `validateOverrides` against
    // `capabilities.knownDevices`. Check #470 for an integration test of that surface.
    assertEquals(1050, captured.overrides?.widthPx)
    assertEquals(2100, captured.overrides?.heightPx)
    assertEquals(2.625f, captured.overrides?.density)
  }

  @Test(timeout = 30_000)
  fun noDeviceOverrideLeavesDimensionsAlone() {
    val captured = renderAndCaptureTarget(overrides = """{"uiMode":"dark"}""")
    // No device → no auto-emitted size tokens. The host's spec defaults take over downstream,
    // matching pre-fix behaviour for the no-device case (the bug only affected the device case).
    assertNull("widthPx must not be set: $captured", captured.overrides?.widthPx)
    assertNull("heightPx must not be set: $captured", captured.overrides?.heightPx)
    assertNull("density must not be set: $captured", captured.overrides?.density)
    assertEquals(UiMode.DARK, captured.overrides?.uiMode)
  }

  @Test(timeout = 30_000)
  fun portraitRotatesALandscapeDeviceFrame() {
    val captured =
      renderAndCaptureTarget(
        overrides = """{"device":"id:pixel_tablet","orientation":"portrait"}"""
      )
    // #3547 — the reported URL: `?device=id:pixel_tablet&orientation=portrait`. Pixel Tablet is
    // 1280x800dp at density 2.0, i.e. 2560x1600px landscape by nature. Portrait must trade the
    // axes, so the wire payload carries 1600x2560. Pre-fix the device's natural landscape pixels
    // went out unchanged and `orientation=portrait` rode along as an inert token.
    assertEquals(1600, captured.overrides?.widthPx)
    assertEquals(2560, captured.overrides?.heightPx)
    assertEquals(2.0f, captured.overrides?.density)
    assertEquals(Orientation.PORTRAIT, captured.overrides?.orientation)
    assertEquals("id:pixel_tablet", captured.overrides?.device)
  }

  @Test(timeout = 30_000)
  fun landscapeRotatesAPortraitDeviceFrame() {
    val captured =
      renderAndCaptureTarget(overrides = """{"device":"id:pixel_5","orientation":"landscape"}""")
    // Pixel 5 is 393x851dp at 2.75 => 1080x2340px portrait; landscape trades the axes.
    assertEquals(2340, captured.overrides?.widthPx)
    assertEquals(1080, captured.overrides?.heightPx)
  }

  @Test(timeout = 30_000)
  fun orientationMatchingTheDeviceFrameLeavesItAlone() {
    val captured =
      renderAndCaptureTarget(
        overrides = """{"device":"id:pixel_tablet","orientation":"landscape"}"""
      )
    // The swap is idempotent: a device already in the requested orientation stays put, so the
    // downstream routers can apply the same rule again without rotating it back.
    assertEquals(2560, captured.overrides?.widthPx)
    assertEquals(1600, captured.overrides?.heightPx)
  }

  @Test(timeout = 30_000)
  fun explicitPixelsBeatTheOrientationRequest() {
    val captured =
      renderAndCaptureTarget(
        overrides = """{"device":"id:pixel_tablet","orientation":"portrait","widthPx":900}"""
      )
    // PROTOCOL.md § 5 precedence: naming exact pixels outranks every derived value, the rotation
    // included. Neither axis is swapped, so the caller's 900 is not silently moved to the height.
    assertEquals(900, captured.overrides?.widthPx)
    assertEquals(1600, captured.overrides?.heightPx)
  }

  @Test(timeout = 30_000)
  fun inspectionModeOverrideThreadsThroughPayload() {
    val captured = renderAndCaptureTarget(overrides = """{"inspectionMode":false}""")

    assertEquals(false, captured.overrides?.inspectionMode)
  }

  @Test(timeout = 30_000)
  fun slotModeOverrideThreadsThroughPayload() {
    val captured = renderAndCaptureTarget(overrides = """{"slotMode":true}""")

    assertEquals(true, captured.overrides?.slotMode)
  }

  @Test(timeout = 30_000)
  fun clearBackgroundOverrideThreadsThroughPayload() {
    val captured = renderAndCaptureTarget(overrides = """{"clearBackground":true}""")

    assertEquals(true, captured.overrides?.clearBackground)
  }

  @Test(timeout = 30_000)
  fun noClearBackgroundOverrideLeavesPayloadClean() {
    val captured = renderAndCaptureTarget(overrides = """{"uiMode":"dark"}""")

    assertNull("clearBackground must not be set: $captured", captured.overrides?.clearBackground)
  }

  /**
   * Spins up a [JsonRpcServer] backed by a [PayloadCapturingHost] with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received.
   */
  private fun renderAndCaptureTarget(overrides: String): RenderTarget.Preview {
    val sourceKt = java.nio.file.Files.createTempFile("device-override-test", ".kt")
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

    val host = PayloadCapturingHost()
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
      Thread({ server.run() }, "device-override-encoding-test").apply { isDaemon = true }
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
        "device-override-encoding-test-reader",
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
 * synchronously with a stub success result. Smaller than `JsonRpcServerIntegrationTest`'s
 * `FakeRenderHost` because we don't need to spy on metrics or interrupt counts here.
 */
private class PayloadCapturingHost : RenderHost {
  val lastTarget: AtomicReference<RenderTarget?> = AtomicReference(null)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()
  @Volatile private var stopped = false
  private val worker =
    Thread(
        {
          while (!stopped) {
            val req = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            when (req) {
              is RenderRequest.Render -> {
                lastTarget.set(req.target)
                results.put(
                  RenderResult(id = req.id, classLoaderHashCode = 0, classLoaderName = "fake")
                )
              }
              // Never enqueued here: `submit` only accepts a Render. Present so the `when` stays
              // exhaustive over `RenderRequest` (issue #3749 added ParameterRows).
              is RenderRequest.ParameterRows -> {}
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
