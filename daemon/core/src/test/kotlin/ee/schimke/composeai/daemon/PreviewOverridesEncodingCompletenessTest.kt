package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.FigmaSvgBackgroundMode
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
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
 * Completeness gate for what a `renderNow` hands the render host (issue #3073).
 *
 * The encoder used to carry a [PreviewOverrides] field in one of exactly two ways: a **typed wire
 * token** (`widthPx=…;uiMode=dark;…`) or a base64 `overrides=<bag>` appended beside them. A field
 * on neither path was accepted by the protocol, merged by `PreviewOverrideMerge`, documented in
 * `Messages.kt` as honoured — and then silently dropped, so the render came back with default
 * pixels and no error. That happened five separate times (`permissions`, `gestures`, `lottie`,
 * `namedOverrides`, `themeProvider`, each fixed one at a time) and had eight more live instances
 * when #3073 was filed (`clockEpochMillis`, `placeholderActive`, `ambient`, `focus`, `keyboard`,
 * `touchOverlay`, `remoteCompose`, `launcherWidget`).
 *
 * The overrides now ride [RenderTarget.Preview] as the object the client sent, so the question is
 * no longer "which of the two paths carries this field" but the stronger "does the host receive
 * what the caller sent". This test still walks `PreviewOverrides.serializer().descriptor` to prove
 * the fixture below covers every declared field, then asserts the whole object survives a real
 * `initialize` → `renderNow` round-trip — which a field can only fail by someone reintroducing a
 * projection between the protocol and the host.
 *
 * Sibling to [PermissionsOverrideEncodingTest] / [ThemeProviderOverrideEncodingTest], which pin the
 * per-field semantics; this one only asks "does it reach the renderer at all".
 */
class PreviewOverridesEncodingCompletenessTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  /**
   * Every [PreviewOverrides] field set to a non-default value, so serializing it (with
   * `encodeDefaults = false`) yields a JSON object whose keys are the full field set. The
   * descriptor check below fails loudly if a newly added field is missing from here.
   */
  private val fullyPopulated =
    PreviewOverrides(
      widthPx = 411,
      heightPx = 891,
      minWidthPx = 100,
      minHeightPx = 120,
      maxWidthPx = 800,
      maxHeightPx = 900,
      density = 2.75f,
      localeTag = "fr-FR",
      fontScale = 1.3f,
      uiMode = UiMode.DARK,
      orientation = Orientation.LANDSCAPE,
      device = "id:pixel_5",
      captureAdvanceMs = 64L,
      clockEpochMillis = 1_700_000_000_000L,
      inspectionMode = false,
      slotMode = true,
      placeholderActive = true,
      clearBackground = true,
      svgBackground = FigmaSvgBackgroundMode.FULL_BLEED,
      material3Theme = Material3ThemeOverrides(colorScheme = mapOf("primary" to "#FF3366FF")),
      themeProvider = "com.example.BrandDarkThemeCatalog",
      wallpaper = WallpaperOverride(seedColor = "#FF8800"),
      ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT),
      gestures = GestureOverride(showHints = true, invoke = GestureKindOverride.PRIMARY),
      focus = FocusOverride(tabIndex = 2, direction = FocusDirection.Next),
      touchOverlay = true,
      talkBack = true,
      keyboard = KeyboardOverride(visible = true, pressedKey = "a"),
      permissions =
        PermissionsOverride(
          grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
        ),
      remoteCompose = RemoteComposeOverride(profile = RemoteComposeProfile.ANDROIDX),
      launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(width = 4, height = 2)),
      lottie = LottieOverride(progress = 0.42f),
      namedOverrides = mapOf("title" to PreviewOverrideValue.StringValue("Hello")),
    )

  /**
   * Guards the fixture itself: a field added to [PreviewOverrides] but not to [fullyPopulated]
   * would otherwise sail through the encoding assertion below (nothing to drop → nothing to
   * notice).
   */
  @Test
  fun fixtureCoversEveryDeclaredField() {
    val declared = declaredFieldNames()
    val populated =
      json.encodeToString(PreviewOverrides.serializer(), fullyPopulated).let {
        json.parseToJsonElement(it).jsonObject.keys
      }
    val missing = declared - populated
    assertTrue(
      "PreviewOverrides gained field(s) $missing — add non-default value(s) for them to " +
        "PreviewOverridesEncodingCompletenessTest.fullyPopulated so the encoding assertion " +
        "actually exercises them.",
      missing.isEmpty(),
    )
  }

  /**
   * The headline assertion: every field the caller set arrives on the host's target, with its value
   * intact.
   *
   * [device] is the one field the daemon deliberately transforms on the way through — it is a
   * catalog token and the backends want pixels, so `renderTargetFor` resolves it into `widthPx` /
   * `heightPx` / `density`. Those three are compared separately below; everything else must be
   * byte-identical to what was sent.
   */
  @Test(timeout = 60_000)
  fun everyOverrideFieldReachesTheHostUnchanged() {
    val sent = fullyPopulated
    val received =
      renderAndCaptureTarget(json.encodeToString(PreviewOverrides.serializer(), sent)).overrides
    assertNotNull("the host must receive the caller's overrides", received)
    assertEquals(
      "a renderNow override was altered or dropped between the protocol and the host " +
        "(issue #3073) — a caller setting it would get default pixels and no error",
      sent.copy(widthPx = null, heightPx = null, density = null),
      received!!.copy(widthPx = null, heightPx = null, density = null),
    )
  }

  /**
   * The explicit `widthPx` / `heightPx` on the fixture outrank the Pixel 5 geometry its `device`
   * would otherwise supply; `density` has no explicit value on the fixture, so it resolves from the
   * catalog. That precedence is PROTOCOL.md § 5's, applied once in `renderTargetFor`.
   */
  @Test(timeout = 60_000)
  fun deviceResolvesToPixelsWithoutOutrankingAnExplicitSize() {
    val received =
      renderAndCaptureTarget(json.encodeToString(PreviewOverrides.serializer(), fullyPopulated))
        .overrides
    assertEquals(411, received?.widthPx)
    assertEquals(891, received?.heightPx)
    assertEquals(2.75f, received?.density)
    assertEquals("id:pixel_5", received?.device)
  }

  @Test(timeout = 60_000)
  fun previouslyDroppedFieldsArriveIntact() {
    // Spot-check the headline regression from #3073 rather than trusting the descriptor walk
    // alone: the deterministic wall clock (#1968) and the loading-state pin (#2646).
    val received =
      renderAndCaptureTarget("""{"clockEpochMillis":1700000000000,"placeholderActive":true}""")
        .overrides
    assertEquals(1_700_000_000_000L, received?.clockEpochMillis)
    assertEquals(true, received?.placeholderActive)
  }

  @Test(timeout = 60_000)
  fun anOverrideFreeRenderCarriesNoOverrides() {
    assertNull(renderAndCaptureTarget("null").overrides)
  }

  private fun declaredFieldNames(): Set<String> {
    val descriptor = PreviewOverrides.serializer().descriptor
    return (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
  }

  /**
   * Spins up a [JsonRpcServer] backed by a payload-capturing host with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * [RenderTarget.Preview] the host received. Mirrors [ThemeProviderOverrideEncodingTest]'s helper
   * — kept file-local for the same reason.
   */
  private fun renderAndCaptureTarget(overrides: String): RenderTarget.Preview {
    val sourceKt = java.nio.file.Files.createTempFile("overrides-completeness-test", ".kt")
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

    val host = PayloadCapturingCompletenessHost()
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
      Thread({ server.run() }, "overrides-completeness-test").apply { isDaemon = true }
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
        "overrides-completeness-test-reader",
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
 * [ThemeProviderOverrideEncodingTest] uses, so the two tests take no cross-file dependency.
 */
private class PayloadCapturingCompletenessHost : RenderHost {
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
                    classLoaderName = "overrides-completeness-test",
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
        "payload-capturing-completeness-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingCompletenessHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
