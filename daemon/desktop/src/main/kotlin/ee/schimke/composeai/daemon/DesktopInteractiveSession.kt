@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Desktop [InteractiveSession] holding a long-lived `ImageComposeScene` (INTERACTIVE.md § 9).
 * `CLICK` becomes press + release; keys and pointers go through [SceneKeyDispatch] /
 * [ScenePointerDispatch], shared with the recording lane. Coordinates are image-natural pixels and
 * are not density-scaled again.
 *
 * **Threading.** Every scene touch (setUp, dispatch, render, [onSceneClose], tearDown) runs on
 * [sceneExecutor], which this session owns. `LaunchedEffect`s resume on whichever thread last drove
 * recomposition, so touching the scene from another thread trips "multithreaded access to
 * SnapshotStateObserver" (issue #1229) and can SIGABRT in Skiko's scene close.
 */
class DesktopInteractiveSession(
  override val previewId: String,
  private val engine: RenderEngine,
  private val state: RenderEngine.SceneState,
  private val sandboxStats: SandboxLifecycleStats,
  /** Owned single-thread executor; the caller must already have run [RenderEngine.setUp] on it. */
  private val sceneExecutor: ExecutorService,
  /**
   * Runs on [sceneExecutor] just before tear-down, so listeners dispose on the thread they were
   * installed on. Failures are logged and do not stop tear-down.
   */
  private val onSceneClose: (() -> Unit)? = null,
  /** Fired once after [close] has torn everything down, on the closing thread. */
  private val onCloseHook: (() -> Unit)? = null,
) : InteractiveSession {

  @Volatile private var closed: Boolean = false

  /**
   * Defaults to [RenderEngine.currentFrameNanoTime], matching the composition's frame clock. CLICK
   * passes explicit times so press and release land a predictable interval apart.
   */
  private val pointers: ScenePointerDispatch =
    ScenePointerDispatch(
      scene = { state.scene },
      defaultTimeMillis = { engine.currentFrameNanoTime() / 1_000_000L },
      defaultFrameNanos = { engine.currentFrameNanoTime() },
      settleFrame = { nanoTime -> engine.renderSettlingFrame(state, nanoTime) },
    )

  override val isClosed: Boolean
    get() = closed

  override fun dispatch(input: InteractiveInputParams) {
    if (closed) return
    runOnSceneThread {
      if (closed) return@runOnSceneThread
      dispatchOnSceneThread(input)
    }
  }

  private fun dispatchOnSceneThread(input: InteractiveInputParams) {
    val resolved = resolvePointerPixels(input)
    val px = resolved?.first
    val py = resolved?.second
    val deviceType = composePointerType(input.pointerType)
    when (input.kind) {
      InteractiveInputKind.CLICK -> {
        if (px == null || py == null) return
        val id = input.pointerId ?: 0
        val offset = sceneOffset(px, py)
        // `press` settles with a render so the tap detector sees the down before the up.
        val nowNs = engine.currentFrameNanoTime()
        val nowMs = nowNs / 1_000_000L
        pointers.press(id, offset, deviceType, timeMillis = nowMs, frameNanos = nowNs)
        pointers.release(id, offset, deviceType, timeMillis = nowMs + CLICK_HOLD_MS)
      }
      InteractiveInputKind.POINTER_DOWN -> {
        if (px == null || py == null) return
        pointers.press(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.POINTER_MOVE -> {
        if (px == null || py == null) return
        pointers.move(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.POINTER_UP -> {
        if (px == null || py == null) return
        pointers.release(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.ROTARY_SCROLL -> {
        if (px == null || py == null) return
        val deltaY = input.scrollDeltaY ?: return
        pointers.scroll(sceneOffset(px, py), deltaY)
      }
      // Undispatchable keys are dropped silently: interactive/input is fire-and-forget.
      InteractiveInputKind.KEY_DOWN ->
        SceneKeyDispatch.keyDown(state.scene, input.keyCode, input.text)
      InteractiveInputKind.KEY_UP -> SceneKeyDispatch.keyUp(state.scene, input.keyCode)
    }
  }

  override fun dispatchLottieProgress(progress: Float): Boolean {
    if (closed) return false
    val clamped = progress.coerceIn(0f, 1f)
    runOnSceneThread {
      if (closed) return@runOnSceneThread
      // Recomposes the held scene; also remembered per preview so a later fresh render keeps it.
      state.lottieProgressState.value = clamped
      state.spec.previewId?.let { LottieProgressController.remember(it, clamped) }
    }
    return !closed
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    check(!closed) { "DesktopInteractiveSession.render() called after close()" }
    return runOnSceneThreadForResult {
      check(!closed) { "DesktopInteractiveSession.render() called after close()" }
      engine.renderOnce(state, requestId, sandboxStats = sandboxStats, useWallClockFrameTime = true)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      runOnSceneThread {
        if (onSceneClose != null) {
          try {
            onSceneClose.invoke()
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-daemon: DesktopInteractiveSession: onSceneClose threw " +
                "(${t.javaClass.simpleName}: ${t.message}); continuing with tearDown"
            )
          }
        }
        engine.tearDown(state)
      }
    } finally {
      sceneExecutor.shutdown()
      try {
        if (!sceneExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          System.err.println(
            "compose-ai-daemon: DesktopInteractiveSession($previewId): sceneExecutor did not " +
              "terminate within ${EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS}s; continuing"
          )
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      if (onCloseHook != null) {
        try {
          onCloseHook.invoke()
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: DesktopInteractiveSession: onCloseHook threw " +
              "(${t.javaClass.simpleName}: ${t.message}); continuing"
          )
        }
      }
    }
  }

  /**
   * Runs [block] on [sceneExecutor] and waits, rethrowing its original exception. Dropped silently
   * when a concurrent [close] already shut the executor down.
   */
  private inline fun runOnSceneThread(crossinline block: () -> Unit) {
    val future =
      try {
        sceneExecutor.submit { block() }
      } catch (_: RejectedExecutionException) {
        return
      }
    try {
      future.get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private inline fun <T> runOnSceneThreadForResult(crossinline block: () -> T): T {
    val future = sceneExecutor.submit<T> { block() }
    return try {
      future.get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    }
  }

  /**
   * Image-natural pixels for [input]: explicit `pixelX`/`pixelY`, else the centre of the single
   * node its semantic `target` resolves to. `null` (logged, dispatch skipped) otherwise. Scene
   * thread only.
   */
  private fun resolvePointerPixels(input: InteractiveInputParams): Pair<Int, Int>? {
    val explicitX = input.pixelX
    val explicitY = input.pixelY
    if (explicitX != null && explicitY != null) return explicitX to explicitY
    val target = input.target?.toSemanticsTarget() ?: return null
    val root =
      engine.laidOutSemanticsRoot(state)
        ?: return logUnresolved(target, "no semantics root available")
    return when (val res = SemanticsTargets.resolve(root, target)) {
      is TargetResolution.Resolved -> res.point.x to res.point.y
      TargetResolution.NotFound -> logUnresolved(target, "no node matched")
      is TargetResolution.Ambiguous ->
        logUnresolved(
          target,
          "${res.candidates.size} nodes matched (refs: " +
            "${res.candidates.mapNotNull { it.ref }}); use a ref to disambiguate",
        )
    }
  }

  private fun logUnresolved(target: SemanticsTarget, reason: String): Pair<Int, Int>? {
    System.err.println(
      "compose-ai-daemon: DesktopInteractiveSession($previewId): target $target unresolved " +
        "($reason); dropping input"
    )
    return null
  }

  /**
   * Image-natural pixels and `ImageComposeScene` pointer positions share one physical-pixel space.
   */
  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    return androidx.compose.ui.geometry.Offset(px.toFloat(), py.toFloat())
  }

  /** For tests that want to peek at the held scene's identity without exposing it permanently. */
  internal fun heldScene(): androidx.compose.ui.ImageComposeScene = state.scene

  companion object {
    /** CLICK press-to-release time, as in Compose's test harness; well short of a long press. */
    private const val CLICK_HOLD_MS: Long = 100L

    /** How long [close] waits for the executor to drain before logging and moving on. */
    private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS: Long = 5L
  }
}
