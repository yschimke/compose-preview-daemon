package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.protocol.ClientCapabilities
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.InitializeResult
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

/**
 * One daemon's life: spawn → wire the handlers → `initialize` → run → shut down, with death
 * observed rather than handled.
 *
 * ### Why this is here
 *
 * Both consumers hand-rolled this loop, and it has ordering subtleties that are invisible from
 * outside. [DaemonSpawn.client]'s own KDoc requires the notification and close handlers to be wired
 * **before the first frame** — a constraint a newcomer violates once and then debugs as a dropped
 * event. Add the handshake timeout, shutting the process down when `initialize` fails (otherwise a
 * failed start leaks a JVM), telling "the daemon died" apart from "we closed it", and making close
 * idempotent from any state. Each is a small thing; a third party gets at least one of them wrong.
 *
 * ### What this deliberately is not
 *
 * **No restart, no backoff, no retry, no in-flight-request policy, no pool, no registry, no
 * eviction, no seat budget.** Those differ per application, and an application that inherits them
 * from a library has to fight its way back out. This type sequences and it reports: when the daemon
 * dies, [Listener.onDied] fires and the **caller** decides whether to respawn, give up, or degrade.
 * That is the negative rule in `docs/design/EMBEDDING.md` — *the daemon never decides how many
 * daemons exist, how long they live, or what happens when one dies* — expressed as a type that
 * cannot break it.
 *
 * The seam is judged by whether a supervisor can be built **on** this without reaching around it:
 * the preview server's `DaemonSupervisor` keeps its registry, its replica groups and its
 * `classpathDirty` respawn, and holds one `ManagedDaemon` per live daemon instead of its own copy
 * of the loop.
 *
 * ### Threading
 *
 * [start] blocks the calling thread for the whole cold start (roughly 600 ms desktop, 3–10 s
 * Robolectric). [Listener] callbacks arrive on the transport's reader thread, so a handler must not
 * call back into a session method that is still waiting (PROTOCOL.md § 2). [state], [close] and
 * [shutdown] are safe from any thread; every other member follows [DaemonSession]'s rules.
 *
 * @property workspaceId which workspace this daemon serves.
 * @property descriptor how to launch it — from [DaemonLaunchPlan.describe] or read off disk.
 */
public class ManagedDaemon(
  public val workspaceId: WorkspaceId,
  public val descriptor: DaemonLaunchDescriptor,
  private val factory: DaemonClientFactory = SubprocessDaemonClientFactory(),
  /**
   * Given at construction, not after [start], because handlers wired after the first frame miss
   * whatever already arrived. Making it a constructor parameter is what removes the ordering
   * mistake rather than documenting it.
   */
  private val listener: Listener = Listener.NONE,
) : Closeable {

  /**
   * Where one daemon is in its life. Forward-only: nothing returns to an earlier state, so a caller
   * that saw [DIED] never sees [READY] again on the same instance — respawning means a new
   * [ManagedDaemon], which is what keeps restart policy on the caller's side.
   */
  public enum class State {
    /** Constructed; no process yet. */
    NEW,
    /** [start] is in flight: spawned, handshake not finished. */
    STARTING,
    /** `initialize` returned. The only state in which [session] and [initializeResult] are live. */
    READY,
    /** [close] or [shutdown] is in flight. */
    CLOSING,
    /** Closed by us, from any earlier state. Terminal. */
    CLOSED,
    /** The transport closed on its own — the daemon exited, crashed, or was killed. Terminal. */
    DIED,
  }

  /**
   * How a daemon's life ended without the caller asking.
   *
   * The wire is all we can observe: a JSON-RPC transport reaching EOF says the daemon is gone, not
   * why. So this reports *when* it happened, which is the part a caller can act on — a death during
   * the handshake usually means the JVM failed to start (a missing artifact, a bad classpath) and a
   * death while [State.READY] usually means a crash or an idle exit. The exit status and the stderr
   * tail belong to whatever spawned the process, and [SubprocessDaemonClientFactory] already
   * forwards that stderr.
   */
  public class DaemonDeath
  internal constructor(
    /** The state the daemon was in when its transport closed. Never a terminal state. */
    public val observedIn: State,
    public val message: String,
  ) {
    override fun toString(): String = message
  }

  /**
   * The events one daemon emits. Every method has a no-op default: an embedder implements the one
   * it cares about.
   *
   * A callback runs on the transport's reader thread and must not block it — the reader thread is
   * also what delivers the response to any call in flight.
   */
  public interface Listener {
    /**
     * A JSON-RPC notification from the daemon. Wired before the first frame; see [ManagedDaemon].
     */
    public fun onNotification(method: String, params: JsonObject?) {}

    /**
     * The daemon ended on its own. The caller decides what happens next — this type never respawns.
     */
    public fun onDied(death: DaemonDeath) {}

    /** Every [State] transition, in order. Useful for logging and for tests. */
    public fun onStateChange(from: State, to: State) {}

    public companion object {
      /** Ignores everything. */
      public val NONE: Listener = object : Listener {}
    }
  }

  private val stateRef = AtomicReference(State.NEW)
  private val lock = Any()

  @Volatile private var spawn: DaemonSpawn? = null
  @Volatile private var client: DaemonClient? = null
  @Volatile private var result: InitializeResult? = null

  public val state: State
    get() = stateRef.get()

  /** The daemon's `initialize` response, or null before [start] returns. */
  public val initializeResult: InitializeResult?
    get() = result

  /**
   * The live session. Throws [IllegalStateException] before [start] succeeds and after the daemon
   * is closed or dead — a call on a dead transport would otherwise fail deep inside the wire with a
   * message about a stream rather than about the daemon.
   */
  public val session: DaemonSession
    get() =
      client?.takeIf { state == State.READY }
        ?: error("daemon ${descriptor.modulePath} is not ready (state=$state)")

  /**
   * Spawn, wire, and complete the `initialize` handshake. Returns the daemon's own
   * [InitializeResult]; the caller is [READY][State.READY] when it does.
   *
   * Failure leaves nothing running: a handshake that throws or times out shuts the process down
   * before this method does, because the alternative is a JVM nobody holds a reference to.
   *
   * @param workspaceRoot absolute path of the workspace the daemon serves.
   * @param moduleProjectDir the module's own directory; defaults to the descriptor's working
   *   directory, which is what every launcher in both consumers passes.
   * @param maxRenderTime per-render ceiling to advertise, or null for the daemon's own.
   * @throws DaemonStartException if the spawn or the handshake fails.
   * @throws IllegalStateException if called more than once.
   */
  public fun start(
    workspaceRoot: String,
    moduleProjectDir: String = descriptor.workingDirectory,
    moduleId: String = descriptor.modulePath,
    capabilities: ClientCapabilities = ClientCapabilities(visibility = true, metrics = true),
    attachDataProducts: List<String>? = null,
    maxRenderTime: Duration? = null,
    timeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
  ): InitializeResult {
    check(stateRef.compareAndSet(State.NEW, State.STARTING)) {
      "daemon ${descriptor.modulePath} was already started (state=${stateRef.get()})"
    }
    listener.onStateChange(State.NEW, State.STARTING)

    val spawned =
      try {
        factory.spawn(workspaceId, descriptor)
      } catch (e: Exception) {
        moveTo(State.CLOSED)
        throw DaemonStartException(
          "Failed to spawn daemon for ${descriptor.modulePath}: ${e.describe()}",
          e,
        )
      }
    spawn = spawned

    // Before the first frame, per DaemonSpawn.client's contract: the handlers have to be baked in
    // when the transport starts reading, or a notification the daemon sends during `initialize` is
    // read and dropped.
    val wired =
      spawned.client(
        onNotification = { method, params -> listener.onNotification(method, params) },
        onClose = { observeTransportClose() },
      )
    client = wired

    val handshake =
      try {
        wired.initialize(
          workspaceRoot = workspaceRoot,
          moduleId = moduleId,
          moduleProjectDir = moduleProjectDir,
          capabilities = capabilities,
          attachDataProducts = attachDataProducts,
          maxRenderMs = maxRenderTime?.inWholeMilliseconds,
          timeout = timeout,
        )
      } catch (e: Exception) {
        // Shut the process down before reporting: a failed handshake otherwise leaves a JVM with
        // no owner, and on the Android backend that is a Robolectric sandbox holding real memory.
        runCatching { spawned.shutdown() }
        moveTo(State.CLOSED)
        throw DaemonStartException(
          "Daemon initialize handshake failed for ${descriptor.modulePath}: ${e.describe()}",
          e,
        )
      }

    result = handshake
    // A daemon that died mid-handshake has already moved to DIED; do not resurrect it.
    if (stateRef.compareAndSet(State.STARTING, State.READY)) {
      listener.onStateChange(State.STARTING, State.READY)
    }
    return handshake
  }

  /**
   * Ask the daemon to exit, then release the transport. Idempotent and safe from any state,
   * including [State.DIED] — closing a dead daemon still reaps the process.
   *
   * @param timeout budget for the whole shutdown, or null for the spawn's own.
   */
  public fun shutdown(timeout: Duration? = null) {
    synchronized(lock) {
      val current = stateRef.get()
      if (current == State.CLOSED || current == State.CLOSING) return
      stateRef.set(State.CLOSING)
      listener.onStateChange(current, State.CLOSING)
      spawn?.let { s -> runCatching { if (timeout == null) s.shutdown() else s.shutdown(timeout) } }
      stateRef.set(State.CLOSED)
      listener.onStateChange(State.CLOSING, State.CLOSED)
    }
  }

  override fun close(): Unit = shutdown()

  /**
   * The transport reached EOF. Ours if we are closing, the daemon's otherwise — which is the whole
   * of "telling died apart from idle" that a caller can observe from this side of the pipe.
   */
  private fun observeTransportClose() {
    val death =
      synchronized(lock) {
        val current = stateRef.get()
        if (current == State.CLOSING || current == State.CLOSED || current == State.DIED) return
        stateRef.set(State.DIED)
        DaemonDeath(
          observedIn = current,
          message =
            when (current) {
              State.STARTING ->
                "daemon ${descriptor.modulePath} exited during the initialize handshake — " +
                  "the JVM usually failed to start (check the daemon's stderr and its classpath)"
              else -> "daemon ${descriptor.modulePath} exited while running"
            },
        )
      }
    listener.onStateChange(death.observedIn, State.DIED)
    listener.onDied(death)
  }

  private fun moveTo(next: State) {
    val previous = stateRef.getAndSet(next)
    if (previous != next) listener.onStateChange(previous, next)
  }

  private fun Exception.describe(): String = message ?: javaClass.simpleName

  public companion object {
    /**
     * Long enough for an Android sandbox to boot on a cold machine. A caller with a tighter budget
     * passes its own — this is a suggestion, not a policy.
     */
    public val DEFAULT_HANDSHAKE_TIMEOUT: Duration = 60.seconds
  }
}

/** A daemon that could not be spawned or could not complete its handshake. */
public class DaemonStartException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
