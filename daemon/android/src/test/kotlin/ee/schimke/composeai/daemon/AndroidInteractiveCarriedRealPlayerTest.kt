package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A **real** Remote Compose document carried on a held session's overrides, played by the **real**
 * connector (`RemoteComposeIrReplay` → `RemoteComposePlayers`) in each player a caller can name —
 * where [AndroidInteractiveCarriedDocumentTest] proves the routing with a fake replay composable.
 *
 * The document is the remote-m3 catalog's `Stepper` (`stepper__ideal__default__compact`, copied
 * from rc-players' `rc-player/compose` jvmTest fixtures): a level rail between an increase and a
 * decrease button, its value a `rememberMutableRemoteFloat` that each button changes with a
 * `ValueFloatExpressionChange`. So a tap that reaches the player moves the rail, and the change has
 * to outlive the document's next per-frame re-declaration of the value to still show a frame later
 * — the regression rc-players fixed for the CMP player (compose-preview-server#1221).
 *
 * For each of `androidx-view`, `androidx-embedded` and `cmp-android`, on a player-only preview id
 * whose class exists nowhere, the carried document is drawn (the frame is not a blank background);
 * for the two players that draw this document's rail ([STEPPING_PLAYERS]), a tap on the increase
 * button changes the frame and the changed state is still there on a later frame.
 *
 * [everyAndroidPlayerTogglesTheCarriedToggleButton] holds all three players to a tap that changes
 * document state and keeps it, on the RemoteCompose-Examples toggle button.
 *
 * One host per test for all three players, because booting its sandboxes is the expensive part.
 */
class AndroidInteractiveCarriedRealPlayerTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun resetStore() {
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun realPlayersPlayACarriedDocumentAndTakeATap() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("carried-real-player").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolve)
    host.start()
    try {
      for (playerId in listOf("androidx-view") + STEPPING_PLAYERS) {
        val session =
          host.acquireInteractiveSession(PLAYER_PREVIEW_ID, LOADER, overrides = carried(playerId))
        try {
          val atRest = frame(session)
          keepFrame("stepper-$playerId-0-rest", atRest)
          assertDrawn("$playerId draws the carried document", atRest)
          if (playerId !in STEPPING_PLAYERS) continue
          session.dispatch(
            InteractiveInputParams(
              frameStreamId = "irrelevant-on-host-side",
              kind = InteractiveInputKind.CLICK,
              pixelX = SIZE_PX / 2,
              // The increase button: the top eighth of the stepper, where rc-players' own click
              // test
              // presses it.
              pixelY = (SIZE_PX * 0.12f).toInt(),
            )
          )
          val tapped = frame(session)
          keepFrame("stepper-$playerId-1-tapped", tapped)
          assertDiffers("$playerId: a tap reaches the carried document", atRest, tapped)
          // The value is re-declared every frame; a change that did not stick is gone by now.
          val later = frame(session)
          keepFrame("stepper-$playerId-2-later", later)
          assertDiffers("$playerId: the tapped state survives later frames", atRest, later)
        } finally {
          session.close()
        }
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * The RemoteCompose-Examples `render/material-toggle-button` document: a `stateLayout` showing
   * `STATUS: OFF` (red) or `STATUS: ON` (green) by an integer that a `TOGGLE STATE` button flips
   * with a `ValueIntegerExpressionChange`. Every Android player must flip it on a tap, keep it
   * flipped on later frames, and flip it back on a second tap.
   *
   * Each tap is delivered the three ways a viewer sends one: a touch `click`, a mouse `click`, and
   * a `pointerDown` … `pointerUp` pair with a frame rendered while the pointer is down (what the
   * web viewer sends once the pointer moves at all between press and release). The players run with
   * `androidx-view` **last**, on a sandbox whose Android clock earlier sessions have already moved
   * on: that is the state a production daemon is always in, and the one where `androidx-view` used
   * to read every press as a long press and drop the tap — see
   * `RobolectricHost.alignHeldInputClock`.
   */
  @Test
  fun everyAndroidPlayerTogglesTheCarriedToggleButton() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("carried-toggle").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolve)
    host.start()
    try {
      for (playerId in listOf("androidx-embedded", "cmp-android", "androidx-view")) {
        val session =
          host.acquireInteractiveSession(
            PLAYER_PREVIEW_ID,
            LOADER,
            overrides = carried(playerId, TOGGLE, TOGGLE_WIDTH_PX, TOGGLE_HEIGHT_PX),
          )
        try {
          val atRest = settled(session)
          keepFrame("toggle-$playerId-0-rest", atRest)
          assertDrawn("$playerId draws the toggle document", atRest)
          assertStatus("$playerId starts", atRest, on = false)
          val button = buttonCentre(atRest)
          var on = false
          for (gesture in TapGesture.entries) {
            val tag = "$playerId/${gesture.label}"
            tap(session, button, gesture)
            on = !on
            val tapped = settled(session)
            keepFrame("toggle-$playerId-${gesture.label}-1-tapped", tapped)
            assertStatus("$tag: after the tap", tapped, on)
            // The integer is the document's state, not a press effect: it has to still be there.
            val later = settled(session)
            keepFrame("toggle-$playerId-${gesture.label}-2-later", later)
            assertStatus("$tag: frames later", later, on)
          }
        } finally {
          session.close()
        }
      }
    } finally {
      host.shutdown()
    }
  }

  /** How a viewer delivers one tap. */
  private enum class TapGesture(val label: String) {
    TOUCH_CLICK("touch-click"),
    MOUSE_CLICK("mouse-click"),
    /** Press, a frame while the pointer is down, release: the web viewer's drag-shaped tap. */
    SPLIT("down-frame-up"),
  }

  private fun tap(session: InteractiveSession, at: Pair<Int, Int>, gesture: TapGesture) {
    fun input(kind: InteractiveInputKind, pointerType: String? = null) =
      session.dispatch(
        InteractiveInputParams(
          frameStreamId = "irrelevant-on-host-side",
          kind = kind,
          pixelX = at.first,
          pixelY = at.second,
          pointerType = pointerType,
        )
      )
    when (gesture) {
      TapGesture.TOUCH_CLICK -> input(InteractiveInputKind.CLICK, "touch")
      TapGesture.MOUSE_CLICK -> input(InteractiveInputKind.CLICK, "mouse")
      TapGesture.SPLIT -> {
        input(InteractiveInputKind.POINTER_DOWN)
        session.render(requestId = RenderHost.nextRequestId())
        input(InteractiveInputKind.POINTER_UP)
      }
    }
  }

  /**
   * A frame once the document's own transitions are over: the toggle's `stateLayout` cross-fades
   * between its two children, so a frame taken straight after the tap can catch both at half alpha.
   */
  private fun settled(session: InteractiveSession): BufferedImage {
    session.render(requestId = RenderHost.nextRequestId(), advanceTimeMs = SETTLE_MS)
    return frame(session)
  }

  private fun frame(session: InteractiveSession): BufferedImage {
    // Two renders, keeping the second: give a press, its ripple and any value animation a frame to
    // land before comparing.
    session.render(requestId = RenderHost.nextRequestId())
    return decode(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)
  }

  private fun carried(
    playerId: String,
    document: ByteArray = STEPPER,
    widthPx: Int = SIZE_PX,
    heightPx: Int = SIZE_PX,
  ): PreviewOverrides =
    PreviewOverrides(
      widthPx = widthPx,
      heightPx = heightPx,
      density = 1.0f,
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.playerId = playerId
            it.documentBase64 = Base64.getEncoder().encodeToString(document)
          }
          .build(),
    )

  private fun resolve(previewId: String): RenderSpec? =
    when (previewId) {
      PLAYER_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.serve.RcPlayerDaemonKt",
          functionName = "Player",
          widthPx = SIZE_PX,
          heightPx = SIZE_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "carried-real-player",
        )
      else -> null
    }

  private companion object {
    const val PLAYER_PREVIEW_ID: String = "ee.schimke.composeai.serve.RcPlayerDaemonKt.Player"

    const val SIZE_PX: Int = 400

    /**
     * The players whose stepper visibly steps and stays stepped. `androidx-view`
     * (`remote-player-view`'s `RemoteComposePlayer`, alpha19) draws this document without its
     * filled buttons and without the level rail, so a step has nothing to move on screen. All it
     * shows for a tap is a faint press ring, sampled while that player animates it on its own clock
     * (`RemoteClock.SYSTEM`), landing either side of [assertDiffers]' 0.2% from run to run: it
     * failed at 0.106% and at 0.158% while this was investigated, once without and once with the
     * input-clock fix, and passed in between. Its taps are held to a real outcome instead — a state
     * change that persists — by [everyAndroidPlayerTogglesTheCarriedToggleButton].
     */
    val STEPPING_PLAYERS: List<String> = listOf("androidx-embedded", "cmp-android")

    val LOADER: ClassLoader = AndroidInteractiveCarriedRealPlayerTest::class.java.classLoader!!

    val STEPPER: ByteArray =
      checkNotNull(
          AndroidInteractiveCarriedRealPlayerTest::class
            .java
            .getResourceAsStream("/rc-fixtures/Stepper-ideal-default-compact.rc")
        ) {
          "missing Stepper fixture"
        }
        .use { it.readBytes() }

    /** The toggle document's own 400 x 450 canvas, so every player lays it out the same way. */
    const val TOGGLE_WIDTH_PX: Int = 400
    const val TOGGLE_HEIGHT_PX: Int = 450

    /** Longer than the toggle's cross-fade, so [settled] sees one status, fully drawn. */
    const val SETTLE_MS: Long = 1_000L

    /** Pixels of status-text colour that count as "that status is showing". */
    const val STATUS_INK: Int = 150

    val TOGGLE: ByteArray =
      checkNotNull(
          AndroidInteractiveCarriedRealPlayerTest::class
            .java
            .getResourceAsStream("/rc-fixtures/material-toggle-button.rc")
        ) {
          "missing toggle-button fixture"
        }
        .use { it.readBytes() }

    /** [img] shows `STATUS: ON` (green) when [on], else `STATUS: OFF` (red), and not the other. */
    fun assertStatus(message: String, img: BufferedImage, on: Boolean) {
      val red = redInk(img)
      val green = greenInk(img)
      val (shown, hidden) = if (on) green to red else red to green
      assertTrue(
        "$message: expected STATUS: ${if (on) "ON" else "OFF"} (red ink $red, green ink $green)",
        shown > STATUS_INK && hidden < STATUS_INK,
      )
    }

    /** The centre of the `TOGGLE STATE` button's `#6200EE` fill, as drawn in [img]. */
    fun buttonCentre(img: BufferedImage): Pair<Int, Int> {
      var minX = Int.MAX_VALUE
      var minY = Int.MAX_VALUE
      var maxX = -1
      var maxY = -1
      for (y in 0 until img.height) for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r in 0x52..0x72 && g < 0x18 && b > 0xD8) {
          minX = minOf(minX, x)
          minY = minOf(minY, y)
          maxX = maxOf(maxX, x)
          maxY = maxOf(maxY, y)
        }
      }
      check(maxX >= 0) { "no TOGGLE STATE button drawn" }
      return (minX + maxX) / 2 to (minY + maxY) / 2
    }

    /** `STATUS: OFF`'s `#FF5252`, within antialiasing tolerance. */
    fun redInk(img: BufferedImage): Int = countInk(img) { r, g, b -> r > 200 && g < 140 && b < 140 }

    /** `STATUS: ON`'s `#4CAF50`, within antialiasing tolerance. */
    fun greenInk(img: BufferedImage): Int =
      countInk(img) { r, g, b -> g > 140 && r < 130 && b < 130 }

    /** Counts matching pixels in the status box (the top half of the canvas). */
    private fun countInk(img: BufferedImage, match: (Int, Int, Int) -> Boolean): Int {
      var n = 0
      for (y in 0 until img.height) for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        if (match((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) n++
      }
      return n
    }

    /**
     * Keeps a frame under `build/carried-player-frames/` for a reviewer, when the tests run from
     * the module directory (Gradle's default). Best effort: a frame that cannot be written is not a
     * test failure.
     */
    fun keepFrame(name: String, img: BufferedImage) {
      runCatching {
        val dir = File("build/carried-player-frames").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
      }
    }

    fun decode(path: String): BufferedImage =
      ImageIO.read(File(path)) ?: error("could not decode $path")

    /** At least 2% of the frame differs from its top-left (background) pixel. */
    fun assertDrawn(message: String, img: BufferedImage) {
      val background = img.getRGB(0, 0)
      var ink = 0
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (img.getRGB(x, y) != background) ink++
      }
      val pct = ink.toDouble() / (img.width * img.height)
      assertTrue("$message: only ${"%.2f".format(pct * 100)}% of the frame is ink", pct >= 0.02)
    }

    /** At least 0.2% of the pixels differ between [a] and [b]. */
    fun assertDiffers(message: String, a: BufferedImage, b: BufferedImage) {
      assertTrue("$message: frames differ in size", a.width == b.width && a.height == b.height)
      var changed = 0
      for (y in 0 until a.height) for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
      }
      val pct = changed.toDouble() / (a.width * a.height)
      assertTrue("$message: only ${"%.3f".format(pct * 100)}% of pixels changed", pct >= 0.002)
    }
  }
}
