@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import ee.schimke.composeai.daemon.protocol.InteractivePointerType

/*
 * Wire input to scene events, shared by [DesktopInteractiveSession] and [DesktopRecordingSession]
 * so a fix lands in both lanes (separate copies drifted: issue #3545). Scene-thread confined;
 * nothing here synchronises.
 */

/** A pointer's scene-space position plus the device class it is being synthesised as. */
internal data class ScenePointer(val offset: Offset, val type: PointerType)

/**
 * Multi-pointer dispatch over a held [ImageComposeScene]. Every event carries all currently-down
 * pointers, because `Modifier.transformable {}` only sees a pinch when ≥ 2 pointers arrive in one
 * event.
 *
 * @param defaultTimeMillis event time when a call site passes `null`: wall clock live, virtual time
 *   under scripted playback. [defaultFrameNanos] is the same for the settling frame.
 * @param settleFrame renders the frame that settles a [press], with the session's full render
 *   discipline ([RenderEngine.renderSettlingFrame]).
 */
internal class ScenePointerDispatch(
  private val scene: () -> ImageComposeScene,
  private val defaultTimeMillis: () -> Long,
  private val defaultFrameNanos: () -> Long,
  private val settleFrame: (nanoTime: Long) -> Unit,
) {

  /** Pressed pointers by id. */
  private val active: MutableMap<Int, ScenePointer> = mutableMapOf()

  /**
   * The device class pointer [id] was pressed as, else [fallback]. A drag must keep its pressed
   * device type or the gesture it started drops it.
   */
  fun heldTypeOr(id: Int, fallback: PointerType): PointerType = active[id]?.type ?: fallback

  /**
   * Presses pointer [id] and settles it with one render. Gesture detectors only anchor on a press
   * once their coroutine has run; a Move in the same tick (a browser drag) would otherwise start a
   * text selection from the old caret instead of the press point (issue #3697).
   */
  fun press(
    id: Int,
    offset: Offset,
    type: PointerType,
    timeMillis: Long? = null,
    frameNanos: Long? = null,
  ) {
    active[id] = ScenePointer(offset, type)
    send(PointerEventType.Press, timeMillis)
    settleFrame(frameNanos ?: defaultFrameNanos())
  }

  fun move(id: Int, offset: Offset, type: PointerType, timeMillis: Long? = null) {
    active[id] = ScenePointer(offset, heldTypeOr(id, type))
    send(PointerEventType.Move, timeMillis)
  }

  /**
   * Releases pointer [id]. It is sent explicitly with `pressed = false` beside the remaining
   * pointers; omitting it would look like a Move and the lift would never be seen.
   */
  fun release(id: Int, offset: Offset, type: PointerType, timeMillis: Long? = null) {
    val held = heldTypeOr(id, type)
    active.remove(id)
    send(PointerEventType.Release, timeMillis, ScenePointer(offset, held) to id)
  }

  /** Wheel / rotary scroll; positive [deltaY] is wheel-down. Null [timeMillis] uses Skiko's. */
  fun scroll(offset: Offset, deltaY: Float, timeMillis: Long? = null) {
    if (timeMillis == null) {
      scene()
        .sendPointerEvent(
          eventType = PointerEventType.Scroll,
          position = offset,
          scrollDelta = Offset(0f, deltaY),
        )
    } else {
      scene()
        .sendPointerEvent(
          eventType = PointerEventType.Scroll,
          position = offset,
          timeMillis = timeMillis,
          scrollDelta = Offset(0f, deltaY),
        )
    }
  }

  private fun send(
    eventType: PointerEventType,
    timeMillis: Long?,
    releasedPointer: Pair<ScenePointer, Int>? = null,
  ) {
    val pointers = buildList {
      for ((pid, pointer) in active) {
        add(
          ComposeScenePointer(
            id = PointerId(pid.toLong()),
            position = pointer.offset,
            pressed = true,
            type = pointer.type,
          )
        )
      }
      if (releasedPointer != null) {
        val (pointer, pid) = releasedPointer
        add(
          ComposeScenePointer(
            id = PointerId(pid.toLong()),
            position = pointer.offset,
            pressed = false,
            type = pointer.type,
          )
        )
      }
    }
    if (pointers.isEmpty()) return
    val anyPressed = pointers.any { it.pressed }
    scene()
      .sendPointerEvent(
        eventType = eventType,
        pointers = pointers,
        buttons = PointerButtons(isPrimaryPressed = anyPressed),
        timeMillis = timeMillis ?: defaultTimeMillis(),
        button = if (eventType == PointerEventType.Press) PointerButton.Primary else null,
      )
  }
}

/**
 * Key dispatch for both lanes. Sends the physical [Key] (for `onKeyEvent` and command keys such as
 * arrows and Backspace) and the typed character, which is the only half that inserts text.
 */
internal object SceneKeyDispatch {

  /**
   * Key-down for [keyCode] (decimal Android `KEYCODE_*`) and/or typed [text]. `false` when neither
   * is dispatchable.
   */
  fun keyDown(scene: ImageComposeScene, keyCode: String?, text: String?): Boolean {
    val key = androidKeycodeToComposeKey(keyCode)
    val typed = printableText(text)
    if (key == null && typed == null) return false
    // Light the matching cap on the soft-keyboard band (a press also makes the band visible).
    KeyboardBandLabels.fromAndroidKeycode(keyCode)?.let(KeyboardController::notifyKeyDown)
    if (key != null) scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
    // Compose inserts text only for events backed by an AWT `KEY_TYPED` (see [typedKeyEvent]).
    // One per UTF-16 unit, so an emoji arrives as its surrogate pair, as AWT delivers it.
    typed?.forEach { scene.sendKeyEvent(typedKeyEvent(key, it)) }
    return true
  }

  /** Key-up for [keyCode]; `false` when unmapped. Has no typed counterpart. */
  fun keyUp(scene: ImageComposeScene, keyCode: String?): Boolean {
    val key = androidKeycodeToComposeKey(keyCode) ?: return false
    KeyboardBandLabels.fromAndroidKeycode(keyCode)?.let(KeyboardController::notifyKeyUp)
    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    return true
  }
}

/** The wire's `pointerType` as a Compose [PointerType]; absent or unknown is touch. */
internal fun composePointerType(wire: String?): PointerType =
  when (InteractivePointerType.parse(wire)) {
    InteractivePointerType.MOUSE -> PointerType.Mouse
    InteractivePointerType.PEN -> PointerType.Stylus
    InteractivePointerType.TOUCH -> PointerType.Touch
  }

/**
 * [text] when it is exactly one printable code point (possibly a surrogate pair), else `null` —
 * which also rejects browser key names such as `Shift` or `ArrowLeft`.
 */
internal fun printableText(text: String?): String? {
  if (text.isNullOrEmpty()) return null
  if (text.codePointCount(0, text.length) != 1) return null
  val codePoint = text.codePointAt(0)
  if (Character.isISOControl(codePoint)) return null
  val block = Character.UnicodeBlock.of(codePoint)
  if (block == null || block == Character.UnicodeBlock.SPECIALS) return null
  return text
}

/**
 * A `KeyDown` typing [ch]. Compose's `isTypedEvent` requires a backing AWT `KEY_TYPED` event with a
 * printable `keyChar`, so one is synthesised; AWT only needs a non-null source component.
 */
internal fun typedKeyEvent(key: Key?, ch: Char): KeyEvent =
  KeyEvent(
    key = key ?: Key.Unknown,
    type = KeyEventType.KeyDown,
    codePoint = ch.code,
    nativeEvent =
      java.awt.event.KeyEvent(
        typedEventSource,
        java.awt.event.KeyEvent.KEY_TYPED,
        System.currentTimeMillis(),
        0,
        java.awt.event.KeyEvent.VK_UNDEFINED,
        ch,
      ),
  )

/** Lazily built so a process that never types never touches AWT component construction. */
private val typedEventSource: java.awt.Component by lazy { java.awt.Canvas() }
