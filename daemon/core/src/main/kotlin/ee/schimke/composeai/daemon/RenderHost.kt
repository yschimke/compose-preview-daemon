package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor
import java.util.concurrent.atomic.AtomicLong

/**
 * Renderer-agnostic seam between [JsonRpcServer] and a render backend (`RobolectricHost`,
 * `DesktopHost`) — see docs/daemon/DESIGN.md § 4. Members appear here only when every backend needs
 * them; per-backend extras stay on the concrete class.
 *
 * **Threading.** Per DESIGN.md § 9, implementations render on a single thread and never cancel
 * mid-render. [submit] blocks; [JsonRpcServer] calls it off the read loop.
 */
public interface RenderHost {

  /** Called once before the first [submit]; the first render may still pay a cold-start cost. */
  public fun start()

  /**
   * Blocks until [request]'s [RenderResult] is available; throws (typically
   * `IllegalStateException`) after [timeoutMs]. [RenderRequest.Shutdown] is not legal here.
   */
  public fun submit(request: RenderRequest, timeoutMs: Long = 60_000): RenderResult

  /** Drains in-flight renders, then stops the render thread. Idempotent. */
  public fun shutdown(timeoutMs: Long = 30_000)

  /**
   * The disposable user-class loader holder this host renders against (docs/daemon/CLASSLOADER.md),
   * or `null` for hosts that load no user classes (fakes). Under a multi-sandbox pool this is slot
   * 0's holder only; mutate through [swapUserClassLoaders], which reaches every slot.
   */
  public val userClassloaderHolder: UserClassLoaderHolder?
    get() = null

  /**
   * Drops every user-class child loader this host holds (every pool slot), so the next render sees
   * recompiled bytecode.
   */
  public fun swapUserClassLoaders() {
    userClassloaderHolder?.swap()
  }

  /**
   * Whether [acquireInteractiveSession] returns a real held-scene session; surfaced as
   * `InitializeResult.capabilities.interactive`. MUST match the [acquireInteractiveSession]
   * override.
   */
  public val supportsInteractive: Boolean
    get() = false

  /**
   * `PreviewOverrides` field names (wire spelling, PROTOCOL.md § 5) this host actually applies;
   * surfaced as `InitializeResult.capabilities.supportedOverrides` so clients can grey out the
   * rest.
   */
  public val supportedOverrides: Set<String>
    get() = emptySet()

  /** Surfaced as `InitializeResult.capabilities.backend`; `null` for fake hosts. */
  public val backendKind: ee.schimke.composeai.daemon.protocol.BackendKind?
    get() = null

  /** The Robolectric `@Config(sdk = ...)` level this host renders against; `null` off Android. */
  public val androidSdk: Int?
    get() = null

  /**
   * Non-pointer [protocol.InteractiveInputKind] wire names (`keyDown`, `keyUp`, `rotaryScroll`)
   * this host dispatches; surfaced as `InitializeResult.capabilities.interactiveControlKinds`.
   * Pointer kinds are implied by [supportsInteractive] and MUST NOT appear here.
   */
  public val supportedInteractiveControlKinds: Set<String>
    get() = emptySet()

  /**
   * Allocates a held-scene [InteractiveSession] for [previewId] (docs/daemon/INTERACTIVE.md § 9).
   * The default throws [UnsupportedOperationException], which [JsonRpcServer] maps to
   * `MethodNotFound` so clients fall back to re-render-on-input.
   *
   * @param classLoader the current user-class child loader; a later recompile does not leak into
   *   the held scene.
   * @param onSessionClosed fired exactly once when the session closes for any reason (explicit
   *   close, idle watchdog, shutdown), on whichever thread closed it — keep it cheap and
   *   thread-safe.
   */
  public fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
    inspectionMode: Boolean? = null,
    onSessionClosed: (() -> Unit)? = null,
    /** Overrides for the held scene (e.g. `touchOverlay`); hosts may ignore them. */
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides? = null,
  ): InteractiveSession =
    throw UnsupportedOperationException(
      "interactive mode unsupported by ${this::class.simpleName ?: this::class.java.name}"
    )

  /**
   * Whether [acquireRecordingSession] returns a real session; surfaced as
   * `InitializeResult.capabilities.recording`. MUST match the [acquireRecordingSession] override.
   */
  public val supportsRecording: Boolean
    get() = false

  /**
   * [ee.schimke.composeai.daemon.protocol.RecordingFormat] wire names this host can encode. A host
   * that supports recording MUST include `"apng"`; mp4/webm only when `ffmpeg` was found.
   */
  public val supportedRecordingFormats: List<String>
    get() = emptyList()

  /**
   * Allocates a held-scene [RecordingSession] for [previewId]. The default throws
   * [UnsupportedOperationException], which [JsonRpcServer] maps to `MethodNotFound`.
   *
   * @param fps virtual-clock frame rate, caller-validated to `[1, 120]`.
   * @param scale output size multiplier, caller-validated to `(0, 8]`; applied at encode time, so
   *   script coordinates stay in image-natural pixels.
   * @param overrides same semantics as `renderNow.overrides`.
   * @param live capture in real time from incoming `recording/input` instead of replaying a posted
   *   script (RECORDING.md § "live mode").
   */
  public fun acquireRecordingSession(
    previewId: String,
    recordingId: String,
    classLoader: ClassLoader,
    fps: Int,
    scale: Float,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
    live: Boolean = false,
  ): RecordingSession =
    throw UnsupportedOperationException(
      "recording unsupported by ${this::class.simpleName ?: this::class.java.name}"
    )

  /**
   * Recording-script events this host's sessions actually dispatch, surfaced in
   * `capabilities.dataExtensions`. Every event MUST be `supported = true` and registered in the
   * sessions' [RecordingScriptHandlerRegistry]; roadmap (`supported = false`) entries are
   * advertised separately by `DaemonMain`.
   */
  public fun recordingScriptEventDescriptors(): List<DataExtensionDescriptor> = emptyList()

  /**
   * The renderable `@PreviewParameter` rows of [previewId]. Discovery reads bytecode and cannot
   * instantiate a provider, so only a host holding the consumer classpath can answer.
   *
   * Implementations return an empty list for a preview without a provider *before* touching a
   * classloader or sandbox — the common case. Throws [IllegalArgumentException] for an unknown id
   * (`InvalidParams`) and, by default, [UnsupportedOperationException] (`MethodNotFound`).
   */
  public fun previewParameterRows(previewId: String): List<PreviewParameterRow> =
    throw UnsupportedOperationException(
      "preview-parameter row enumeration unsupported by " +
        (this::class.simpleName ?: this::class.java.name)
    )

  public companion object {
    /** JVM-wide monotonic render-request ids, so log correlation survives host restarts. */
    private val nextId: AtomicLong = AtomicLong(1)

    public fun nextRequestId(): Long = nextId.getAndIncrement()
  }
}

/** Request envelope. [Shutdown] is the poison pill; everything else is a [Render]. */
public sealed interface RenderRequest {

  /**
   * Render one preview. [target] says what to render — see [RenderTarget] for why a request is
   * unresolved when it leaves the JSON-RPC layer and resolved by the time it reaches an engine.
   */
  public data class Render(
    val id: Long = RenderHost.nextRequestId(),
    val target: RenderTarget,
  ) : RenderRequest {

    /**
     * [target] as JSON for the sandbox classloader and worker-process crossings. A property because
     * the sandbox reads it reflectively (`getTargetJson`): its copy of this class is a different
     * `Class`, and only `java.*` types survive the trip (see `DaemonHostBridge`).
     */
    val targetJson: String
      get() = RenderTarget.encode(target)
  }

  /**
   * Enumerates a `@PreviewParameter` provider's row labels. A queued request rather than a host
   * call because on Android the provider must run inside the sandbox; the reply is a
   * `java.util.List<String>` in provider order, which crosses the classloader boundary intact.
   */
  public data class ParameterRows(
    val id: Long = RenderHost.nextRequestId(),
    /** FQN of the `PreviewParameterProvider` to enumerate. */
    val providerClassName: String,
    /** Mirrors `@PreviewParameter.limit`; the sandbox clamps it to its own scan ceiling. */
    val limit: Int = Int.MAX_VALUE,
  ) : RenderRequest

  /** Singleton poison pill. */
  public data object Shutdown : RenderRequest
}

/**
 * One `@PreviewParameter` row of a parameterized preview, as reported by
 * [RenderHost.previewParameterRows].
 *
 * [id] is the addressable previewId — `<baseId>_<label>`, the same stem the fan-out renderer writes
 * to disk — so a client lists rows and renders one without constructing ids itself.
 */
public data class PreviewParameterRow(
  /** Zero-based position in the provider's value sequence. */
  val index: Int,
  /**
   * The row token: a derived label (`Dark`) or `PARAM_<index>` when no label could be derived or
   * two values collided. See docs/RENDER_FILENAMES.md.
   */
  val label: String,
  /** The addressable previewId for this row. */
  val id: String,
)
