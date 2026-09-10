package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.ClientCapabilities
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.ExtensionsDisableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamStartResult
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement

/**
 * Everything a caller can ask of one live daemon — the protocol as a Kotlin type.
 *
 * **Why this is an interface and [DaemonClient] is one implementation.** `DaemonClient` is a
 * concrete class that owns a stdio transport and a reader thread. A consumer that wants the *calls*
 * without those had no way to express it, so compose-ai-tools declared its own `RenderSession`
 * interface and wrote `DaemonClientRenderSession` to bridge the two — 311 lines in which every
 * method is a one-line forward. The next embedder writes the same file, because the missing piece
 * is the same: a name for "a daemon you can talk to" that is not also "a subprocess over pipes".
 *
 * Publishing the interface removes the reason to write that adapter, and gives every consumer and
 * every test a seam that does not require a JVM to exist. The methods, their parameters and their
 * defaults are exactly `DaemonClient`'s — this is an extraction, not a redesign, and
 * `DaemonSessionConformanceTest` pins that the two agree.
 *
 * ### Threading
 *
 * Calls are synchronous and block until the daemon answers or [Duration] elapses. Notifications
 * arrive out-of-band on the implementation's own channel (for `DaemonClient`, the reader thread's
 * `onNotification` callback), so a handler must not call back into a session method that is still
 * waiting — see PROTOCOL.md § 2.
 *
 * ### Lifecycle
 *
 * [initialize] first, exactly once. [close] releases the transport without asking the daemon to
 * exit; [shutdownAndExit] asks it to exit and then releases. A caller that owns the process wants
 * the latter, and one that borrowed a session wants the former.
 */
public interface DaemonSession : Closeable {

  /** Drives `initialize` + `initialized`. Returns the daemon's [InitializeResult]. */
  public fun initialize(
    workspaceRoot: String,
    moduleId: String,
    moduleProjectDir: String,
    capabilities: ClientCapabilities = ClientCapabilities(visibility = true, metrics = true),
    attachDataProducts: List<String>? = null,
    maxRenderMs: Long? = null,
    timeout: Duration = 30.seconds,
  ): InitializeResult

  /** Which previews the client is showing; the daemon prioritises these. */
  public fun setVisible(ids: List<String>)

  /** Which preview has the user's attention; outranks [setVisible] for scheduling. */
  public fun setFocus(ids: List<String>)

  /** Tell the daemon a file moved underneath it. */
  public fun fileChanged(
    path: String,
    kind: FileKind = FileKind.SOURCE,
    changeType: ChangeType = ChangeType.MODIFIED,
  )

  /** Render now, ahead of whatever the daemon would have scheduled. */
  public fun renderNow(
    previews: List<String>,
    tier: RenderTier = RenderTier.FULL,
    reason: String? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RenderNowResult

  public fun historyList(
    params: HistoryListParams = HistoryListParams(),
    timeout: Duration = 30.seconds,
  ): HistoryListResult

  public fun historyRead(
    entryId: String,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): HistoryReadResultDto

  public fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode = HistoryDiffMode.METADATA,
    timeout: Duration = 30.seconds,
  ): HistoryDiffResult

  public fun dataFetch(
    previewId: String,
    kind: String,
    params: JsonElement? = null,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): DataFetchResult

  public fun dataSubscribe(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  public fun dataUnsubscribe(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  public fun extensionsList(timeout: Duration = 15.seconds): ExtensionsListResult

  public fun extensionsEnable(
    ids: List<String>,
    timeout: Duration = 15.seconds,
  ): ExtensionsEnableResult

  public fun extensionsDisable(
    ids: List<String>,
    timeout: Duration = 15.seconds,
  ): ExtensionsDisableResult

  public fun recordingStart(
    previewId: String,
    fps: Int? = null,
    scale: Float? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RecordingStartResult

  public fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>)

  public fun recordingStop(
    recordingId: String,
    timeout: Duration = 5.minutes,
  ): RecordingStopResult

  public fun recordingEncode(
    recordingId: String,
    format: RecordingFormat = RecordingFormat.APNG,
    timeout: Duration = 60.seconds,
  ): RecordingEncodeResult

  public fun streamStart(
    previewId: String,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): StreamStartResult

  public fun streamStop(frameStreamId: String)

  public fun streamVisibility(frameStreamId: String, visible: Boolean, fps: Int? = null)

  public fun interactiveInput(
    frameStreamId: String,
    kind: InteractiveInputKind,
    pixelX: Int? = null,
    pixelY: Int? = null,
    pointerId: Int? = null,
    scrollDeltaY: Float? = null,
    keyCode: String? = null,
    text: String? = null,
    pointerType: String? = null,
  )

  /**
   * Ask the daemon to exit, then release the transport.
   *
   * Distinct from [close], which releases this end only. A caller that spawned the process wants
   * this one; a caller handed a session by someone else almost certainly does not.
   */
  public fun shutdownAndExit(timeout: Duration = 15.seconds)
}
