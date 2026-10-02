package ee.schimke.composeai.daemon.remotecompose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkage
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkageReport
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * A captured Remote Compose document, as the bytes a player reads, plus which clock it plays
 * against.
 *
 * Bytes rather than any player's own document type, because every player parses its own: the
 * AndroidX players build a `RemoteDocument`, a JVM or browser player its own model. Hold one
 * instance per document (`remember` it) — a player memoises its parse on the instance.
 */
class RemoteComposeDocumentSource(
  val bytes: ByteArray,
  /**
   * The time source animations advance against. [RemoteComposeClock.SYSTEM] is the player's own;
   * [RemoteComposeClock.ROBOLECTRIC_UPTIME] follows Robolectric's paused looper, so a capture that
   * advances the shadow clock gets the same frame cadence on every player.
   */
  val clock: RemoteComposeClock = RemoteComposeClock.SYSTEM,
)

enum class RemoteComposeClock {
  SYSTEM,
  ROBOLECTRIC_UPTIME,
}

/**
 * One way of drawing a Remote Compose document.
 *
 * The connector draws only through this, so the code that binds to a player library — alpha, and
 * supplied by the consumer at whatever version it brings — lives in each backend's own package and
 * nowhere else. [linkedPackages] names those packages, and [RemoteComposePlayers] checks their
 * bytecode against the classpath before the backend is first used, so a player that has moved
 * underneath us is refused by name rather than failing as a `NoSuchMethodError` mid-composition.
 *
 * Three players are built in: the two AndroidX ones and the CMP player (`cmp-android`). Another
 * player — an rc-players backend, a JVM or CMP player — joins by implementing this and registering
 * it under
 * `META-INF/services/ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayerBackend`; it is
 * then reachable through [RemoteComposePlayers.forId] with the same linkage check.
 */
interface RemoteComposePlayerBackend {
  /** Canonical name, as `?rcPlayer=` and [RemoteComposePlayers.forId] spell it. */
  val id: String

  /** Every other name this player answers to — historical wire spellings that stay accepted. */
  val aliases: Set<String>
    get() = emptySet()

  /**
   * What a capture's `.remotecompose.json` records as its `capturePlayer`. Defaults to [id]; the
   * built-ins keep the historical spellings readers already key on.
   */
  val capturePlayerName: String
    get() = id

  /**
   * The packages (each one exactly, not its subpackages) whose classes call into the player
   * library. Everything that does must be in one of them, or the linkage check cannot see it.
   */
  val linkedPackages: List<String>

  /** Draws [document], with [namedValues] seeded over its authored defaults. */
  @Composable
  fun Play(
    document: RemoteComposeDocumentSource,
    namedValues: Map<String, RemoteNamedValue>,
    modifier: Modifier,
  )
}

/**
 * Thrown in place of using a player whose library this classpath cannot satisfy. Deliberately not a
 * fallback to another player: what drew the pixels is part of the render, so asking for one player
 * and silently getting another would publish a wrong answer.
 */
class RemoteComposeLinkageException(what: String, val report: RemoteComposeLinkageReport) :
  IllegalStateException(
    "$what cannot run against the Remote Compose libraries on this classpath — " +
      "${report.missing.size} reference(s) the consumer's version does not provide: " +
      report.missing.take(MAX_LISTED).joinToString() +
      (if (report.missing.size > MAX_LISTED) ", …" else "") +
      ". Align the consumer's androidx.compose.remote / third-party-rc-embedded-player versions " +
      "with the ones this connector was built against."
  ) {
  private companion object {
    const val MAX_LISTED = 8
  }
}

/** A request named a player nothing on this classpath answers to. */
class RemoteComposeUnknownPlayerException(val playerId: String, val knownNames: List<String>) :
  IllegalArgumentException(
    "No Remote Compose player answers to '$playerId'. Known: ${knownNames.joinToString()}. A " +
      "player beyond the built-ins must be on the classpath and registered under " +
      "META-INF/services/${RemoteComposePlayerBackend::class.java.name}."
  )

/** The players this connector can draw with, each linkage-checked once per class loader. */
object RemoteComposePlayers {
  private val builtIns: Map<RemoteComposePlayerKind, String> =
    mapOf(
      RemoteComposePlayerKind.EMBEDDED to
        "ee.schimke.composeai.daemon.remotecompose.androidx.embedded.AndroidxEmbeddedPlayerBackend",
      RemoteComposePlayerKind.VIEW to
        "ee.schimke.composeai.daemon.remotecompose.androidx.view.AndroidxViewPlayerBackend",
    )

  /**
   * Built-ins reachable by id only, not by a [RemoteComposePlayerKind]: the wire's `player` field
   * names the two AndroidX players and nothing else, so a third built-in rides `playerId`.
   */
  private val builtInsById: List<String> =
    listOf("ee.schimke.composeai.daemon.remotecompose.rcplayer.CmpAndroidPlayerBackend")

  private val checked = ConcurrentHashMap<String, Result<RemoteComposePlayerBackend>>()

  /** The built-in player for [kind], linkage-checked; throws [RemoteComposeLinkageException]. */
  fun forKind(kind: RemoteComposePlayerKind): RemoteComposePlayerBackend {
    val className = builtIns.getValue(kind)
    return checked
      .getOrPut(className) { runCatching { verified(instantiate(className)) } }
      .getOrThrow()
  }

  /**
   * The player answering to [name] — a built-in's canonical name or alias, or a registered
   * backend's — linkage-checked; null when nothing answers to it. Case- and whitespace-insensitive.
   */
  fun forId(name: String): RemoteComposePlayerBackend? {
    val wanted = name.trim().lowercase()
    val candidate =
      candidates().firstOrNull {
        wanted == it.id.lowercase() || wanted in it.aliases.map(String::lowercase)
      } ?: return null
    return checked
      .getOrPut(candidate.javaClass.name) { runCatching { verified(candidate) } }
      .getOrThrow()
  }

  /**
   * The player a render asked for: [playerId] when set (any player [forId] resolves), else the
   * built-in [kind], else [default] — the order `RemoteComposeOverride` documents for its
   * `playerId` and `player` fields, with the build-wide setting last.
   *
   * An id nothing answers to throws [RemoteComposeUnknownPlayerException], naming every player that
   * would have: what draws is part of the answer, so an unknown name is never swapped for the
   * default.
   */
  fun resolve(
    playerId: String?,
    kind: RemoteComposePlayerKind?,
    default: RemoteComposePlayerKind,
  ): RemoteComposePlayerBackend {
    val id = playerId?.takeIf { it.isNotBlank() } ?: return forKind(kind ?: default)
    return forId(id) ?: throw RemoteComposeUnknownPlayerException(id, knownNames())
  }

  /** Every name some player answers to — canonical ids first, then aliases — for diagnostics. */
  fun knownNames(): List<String> {
    val players = candidates().toList()
    return players.map { it.id } + players.flatMap { it.aliases }.sorted()
  }

  /** The linkage of [backend] against this classpath, without refusing anything. */
  fun linkage(backend: RemoteComposePlayerBackend): RemoteComposeLinkageReport =
    RemoteComposeLinkage.check(backend.javaClass, backend.linkedPackages)

  /** The linkage of the built-in for [kind]; for checks that report rather than refuse. */
  fun linkage(kind: RemoteComposePlayerKind): RemoteComposeLinkageReport =
    linkage(instantiate(builtIns.getValue(kind)))

  private fun candidates(): Sequence<RemoteComposePlayerBackend> =
    (builtIns.values + builtInsById).asSequence().map(::instantiate) +
      ServiceLoader.load(
          RemoteComposePlayerBackend::class.java,
          RemoteComposePlayers::class.java.classLoader,
        )
        .asSequence()

  private fun verified(backend: RemoteComposePlayerBackend): RemoteComposePlayerBackend {
    val report = linkage(backend)
    if (!report.isLinked) {
      throw RemoteComposeLinkageException("Remote Compose player '${backend.id}'", report)
    }
    return backend
  }

  // Loaded by name so this file, which every render passes through, references no backend class —
  // and through them no player library — until one is asked for.
  private fun instantiate(className: String): RemoteComposePlayerBackend =
    Class.forName(className, true, RemoteComposePlayers::class.java.classLoader)
      .getDeclaredConstructor()
      .newInstance() as RemoteComposePlayerBackend
}
