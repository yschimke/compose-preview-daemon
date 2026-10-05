package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.RemoteComposeChange

/**
 * Held-scene interactive session for one `frameStreamId` (docs/daemon/INTERACTIVE.md § 9). The
 * scene stays warm across `interactive/input`, so `remember`'d state survives between [dispatch]
 * calls. [JsonRpcServer] allocates it at `interactive/start`, drives it with [dispatch] + [render],
 * and [close]s it at `interactive/stop` or shutdown.
 *
 * Calls on one instance are serialised by the caller. [close] must drain an in-flight render rather
 * than interrupt it (DESIGN.md § 9).
 *
 * The optional `dispatch*` / `capture*` members default to "unsupported" (`false` / `null`) so the
 * caller can report it without failing the session; they throw only when the action itself failed.
 */
public interface InteractiveSession : AutoCloseable {

  /** The preview id this session is rendering. Frozen at allocation time. */
  public val previewId: String

  /**
   * `true` once closed, explicitly or by a host watchdog. Lets the render loop tell "session gone,
   * stop" from "render failed, keep the session for the next input".
   */
  public val isClosed: Boolean
    get() = false

  /**
   * Feeds one input into the held composition without rendering, so several inputs can be batched
   * before one [render]. `CLICK` becomes press+release; coordinates are image-natural pixels and
   * must not be density-scaled again.
   */
  public fun dispatch(input: InteractiveInputParams)

  /**
   * Applies a Remote Compose state edit to the live `RemoteComposeController`; the controller's
   * snapshot state recomposes on its own. `false` means no live binding — the caller re-renders
   * with `overrides.remoteCompose` instead.
   */
  public fun dispatchRemoteComposeChange(change: RemoteComposeChange): Boolean = false

  /**
   * Sets the held scene's Lottie progress (`0f..1f`, clamped) so a timeline scrub recomposes
   * instead of building a new scene per tick. `false` means no live binding — the caller re-renders
   * with `overrides.lottie.progress`.
   */
  public fun dispatchLottieProgress(progress: Float): Boolean = false

  /**
   * Invokes the `SemanticsActions` action [actionKind] (`"click"`, `"longClick"`, `"focus"`, …) on
   * the node whose content description exactly equals [nodeContentDescription], searching the
   * unmerged tree. `false` when no node matched or it lacks the action.
   */
  public fun dispatchSemanticsAction(actionKind: String, nodeContentDescription: String): Boolean =
    false

  /**
   * Invokes a UIAutomator-style action on the node matched by [selectorJson] (`SelectorJson` from
   * `:data-uiautomator-core`, decoded sandbox-side). `false` when no node matched or it lacks the
   * action.
   *
   * @param useUnmergedTree `false` matches on-device UIAutomator, so `By.text("Submit")` targets
   *   the whole `Button`.
   * @param inputText payload for `actionKind = "inputText"`; ignored otherwise.
   */
  public fun dispatchUiAutomator(
    actionKind: String,
    selectorJson: String,
    useUnmergedTree: Boolean = false,
    inputText: String? = null,
  ): Boolean = false

  /**
   * After [dispatchUiAutomator] returned `false`, explains why: match count, the closest near-match
   * node and the attempted action. `null` falls back to a free-form message.
   */
  public fun findUiAutomatorEvidence(
    actionKind: String,
    selectorJson: String,
    useUnmergedTree: Boolean = false,
    inputText: String? = null,
  ): ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReason? = null

  /**
   * Snapshot of the live unmerged semantics tree for a `recording.probe` marker, which
   * [RecordingTestGenerator] diffs into exists/doesNotExist assertions.
   */
  public fun captureProbeSemantics():
    List<ee.schimke.composeai.daemon.protocol.RecordingProbeNode>? = null

  /**
   * Runs ATF against the live held scene for an `assert.a11y` script point. `null` off Android,
   * where there is no `View` hierarchy for ATF to check.
   */
  public fun captureA11yFindings():
    List<ee.schimke.composeai.daemon.protocol.RecordingA11yFinding>? = null

  /**
   * Moves the held activity to [lifecycleEvent] (`"pause"`, `"resume"`, `"stop"`). `"destroy"` is
   * deliberately unsupported: it would tear down the scenario mid-recording. Unknown names yield
   * `false`.
   */
  public fun dispatchLifecycle(lifecycleEvent: String): Boolean = false

  /**
   * Rebuilds the composition from scratch under a new `key(...)`: both `remember` and
   * `rememberSaveable` state reset. Use [dispatchStateRecreate] to keep saveable state.
   */
  public fun dispatchPreviewReload(): Boolean = false

  /**
   * Compose-level equivalent of an activity recreate: saves `rememberSaveable` state, rebuilds,
   * restores. `remember` state is lost.
   */
  public fun dispatchStateRecreate(): Boolean = false

  /** Stores the current `SaveableStateRegistry` snapshot under [checkpointId], overwriting. */
  public fun dispatchStateSave(checkpointId: String): Boolean = false

  /**
   * Rebuilds the composition with the snapshot saved under [checkpointId]; `false` when there is
   * none.
   */
  public fun dispatchStateRestore(checkpointId: String): Boolean = false

  /**
   * Fires a deep link, a back press, or one predictive-back phase at the held activity.
   *
   * @param actionKind `"deepLink"`, `"back"`, or a `"predictiveBack*"` phase (`Started`,
   *   `Progressed`, `Committed`, `Cancelled`); unknown kinds yield `false`.
   * @param deepLinkUri for `deepLink`, sent as an `ACTION_VIEW` intent.
   * @param backProgress for started/progressed, `0.0..1.0`.
   * @param backEdge for started/progressed, `"left"` (default) or `"right"`.
   */
  public fun dispatchNavigation(
    actionKind: String,
    deepLinkUri: String? = null,
    backProgress: Float? = null,
    backEdge: String? = null,
  ): Boolean = false

  /**
   * Settles and encodes the current composition to a PNG; callable repeatedly.
   *
   * @param requestId forwarded to [RenderResult.id].
   * @param advanceTimeMs virtual-clock advance before capture; `null` uses the backend's settle
   *   window. Recordings pass frame deltas to keep animation paced to fps.
   */
  public fun render(requestId: Long, advanceTimeMs: Long? = null): RenderResult

  /**
   * Drains any in-flight render, frees the scene and deletes session-owned files. Idempotent and
   * safe during shutdown with input queued.
   */
  override fun close()
}
