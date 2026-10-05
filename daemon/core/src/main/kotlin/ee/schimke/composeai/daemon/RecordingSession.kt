package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence

/**
 * Held-scene recording session for one `recordingId` (docs/daemon/RECORDING.md). Input and frame
 * ticks share a **virtual clock** at [fps], so script timing — not agent latency — sets the
 * animation timing in the video.
 *
 * Lifecycle, driven by [JsonRpcServer]: `recording/start` allocates, `recording/script` calls
 * [postScript], `recording/stop` calls [stop] (frames to disk), `recording/encode` calls [encode].
 * Frames and encoded video outlive [close] so they can be re-encoded.
 *
 * Calls on one instance are serialised by the caller (Skiko is not thread-safe). [close] must drain
 * an in-flight render rather than interrupt it (DESIGN.md § 9).
 */
public interface RecordingSession : AutoCloseable {

  /** The preview id this session is recording. Frozen at allocation time. */
  public val previewId: String

  /** Opaque session id assigned by [JsonRpcServer] at `recording/start`. Frozen at allocation. */
  public val recordingId: String

  /** Frame rate of the virtual clock, in frames per second. Frozen at allocation. */
  public val fps: Int

  /** Output-frame size multiplier (≥ 0). Frozen at allocation. */
  public val scale: Float

  /**
   * `true`: a background tick thread captures at [fps] and only [postInput] is legal (RECORDING.md
   * § "live mode"). `false`: only [postScript] is legal and [stop] replays it.
   */
  public val live: Boolean

  /**
   * Scripted mode: merges [events] into the timeline, re-sorted by `tMs`. Throws
   * [IllegalStateException] when [live].
   */
  public fun postScript(events: List<RecordingScriptEvent>)

  /**
   * Live mode: queues [input], stamped with elapsed wall-clock time, for the next frame. Does not
   * wait; input after [stop] is dropped. Throws [IllegalStateException] when not [live].
   */
  public fun postInput(input: RecordingInputParams)

  /**
   * Scripted: replays the timeline, one PNG per virtual frame. Live: joins the tick thread. Either
   * way the scene is closed afterwards; a second call returns the same result.
   */
  public fun stop(): RecordingResult

  /** Encodes [stop]'s frames into one video file; throws before [stop]. Idempotent per format. */
  public fun encode(format: RecordingFormat): EncodedRecording

  /** Drains playback and frees the scene; frames and videos stay on disk. Idempotent. */
  override fun close()
}

/**
 * Metadata returned by [RecordingSession.stop]. The on-disk PNGs at `<framesDir>/frame-NNNNN.png`
 * outlive the session.
 */
public data class RecordingResult(
  val frameCount: Int,
  val durationMs: Long,
  val framesDir: String,
  val frameWidthPx: Int,
  val frameHeightPx: Int,
  val scriptEvents: List<RecordingScriptEvidence> = emptyList(),
  /** Timeline captured from a live recording; source of [RecordingStopResult.capturedScript]. */
  val capturedScript: List<RecordingScriptEvent> = emptyList(),
)

/** Metadata returned by [RecordingSession.encode]. */
public data class EncodedRecording(val videoPath: String, val mimeType: String, val sizeBytes: Long)

public fun String.toInteractiveInputKindOrNull(): InteractiveInputKind? =
  INTERACTIVE_INPUT_KIND_BY_WIRE_NAME[this]

/** Recording-script `kind` names for each [InteractiveInputKind]; the single source of truth. */
public val INTERACTIVE_INPUT_KIND_BY_WIRE_NAME: Map<String, InteractiveInputKind> =
  mapOf(
    "input.click" to InteractiveInputKind.CLICK,
    "input.pointerDown" to InteractiveInputKind.POINTER_DOWN,
    "input.pointerMove" to InteractiveInputKind.POINTER_MOVE,
    "input.pointerUp" to InteractiveInputKind.POINTER_UP,
    "input.rotaryScroll" to InteractiveInputKind.ROTARY_SCROLL,
    "input.keyDown" to InteractiveInputKind.KEY_DOWN,
    "input.keyUp" to InteractiveInputKind.KEY_UP,
  )

private val INTERACTIVE_INPUT_KIND_TO_WIRE_NAME: Map<InteractiveInputKind, String> =
  INTERACTIVE_INPUT_KIND_BY_WIRE_NAME.entries.associate { (wire, kind) -> kind to wire }

/**
 * Wire-name string for an [InteractiveInputKind] (the reverse of [toInteractiveInputKindOrNull]).
 */
public fun InteractiveInputKind.wireName(): String =
  INTERACTIVE_INPUT_KIND_TO_WIRE_NAME.getValue(this)
