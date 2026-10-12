package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkage

/**
 * A Remote Compose document **carried** on a request (`overrides.remoteCompose.documentBase64`) on
 * the desktop daemon: decoded, its requested player checked, and the player that will draw it.
 *
 * The desktop twin of what `RobolectricHost` does with the same override. Where the Android daemon
 * draws a carried document through the AndroidX players (or the CMP player on Android), the desktop
 * daemon has exactly one player: the CMP player's JVM host, `cmp-jvm` (rc-players'
 * `rc-player-compose`, JVM target). [RenderEngine.setUp] composes it in place of the preview, so
 * the one-shot render, the held interactive / `stream/start` session and a recording all play the
 * caller's bytes — on an id the manifest does not know as readily as on a borrowed catalog preview,
 * whose own content is then never drawn.
 */
internal class CarriedRemoteComposeDocument(
  val bytes: ByteArray,
  val override: RemoteComposeOverride,
  val player: CarriedDocumentPlayer,
)

/**
 * The seam between the daemon and the player library, implemented by
 * `ee.schimke.composeai.daemon.rcplayer.CmpJvmPlayerBinding` in this module's `rcplayerBinding`
 * source set.
 *
 * **Why a separate source set, loaded by name.** `rc-player-compose` is published under
 * `ee.schimke.composeai`, which `checkDependencyOwnership` keeps off this module's production
 * runtime: the host supplies the player (as it supplies the AndroidX family to the Android daemon),
 * so it cannot be a dependency. And the player is built against a newer Compose Multiplatform line
 * than this module, so compiling the daemon itself against it would quietly move the published
 * module's compile line. The binding is the only code compiled against the player; it ships in this
 * module's jar, and [CarriedRemoteCompose] reaches it reflectively after checking its bytecode
 * against the classpath, so a daemon without the player refuses a carried document by name rather
 * than failing mid-composition.
 */
internal interface CarriedDocumentPlayer {
  /** The player id, as `remoteCompose.playerId` spells it. */
  val id: String

  /**
   * Draws [bytes], with [namedValues] seeded over the document's authored defaults, on [clock] —
   * the document's wall clock (`TimeAttribute`, calendar variables). Animations follow the scene's
   * frame clock as for any composable.
   */
  @Composable
  fun Play(
    bytes: ByteArray,
    namedValues: Map<String, RemoteNamedValue>,
    clock: CarriedDocumentClock,
    modifier: Modifier,
  )
}

/**
 * The wall clock a carried document plays against: epoch milliseconds are the scene frame time
 * elapsed since the first frame this scene rendered, read in UTC.
 *
 * The counterpart of the Android players' Robolectric clock (`RobolectricRcTimeSource`), and for
 * the same reason: on the host's real wall clock, anything a document derives from its time
 * variables (an indeterminate progress sweep, a watch face's hands) would be captured at an
 * arbitrary phase. Driven off the frame timestamps [RenderEngine.SceneState] records, so each lane
 * gets the clock it already runs on — the one-shot render's frozen frame zero (the epoch,
 * deterministic), a live session's wall-clock frames, a scripted recording's virtual timeline.
 */
internal class CarriedDocumentClock {
  @Volatile private var originNanos: Long = UNSET

  @Volatile private var latestNanos: Long = 0L

  /** Notes that the scene rendered (or is about to render) a frame at [nanoTime]. */
  fun onFrame(nanoTime: Long) {
    if (originNanos == UNSET) originNanos = nanoTime
    latestNanos = nanoTime
  }

  /** Milliseconds of frame time since the first frame; `0` before it. */
  fun elapsedMillis(): Long {
    val origin = originNanos
    if (origin == UNSET) return 0L
    return ((latestNanos - origin) / 1_000_000L).coerceAtLeast(0L)
  }

  private companion object {
    const val UNSET: Long = Long.MIN_VALUE
  }
}

internal object CarriedRemoteCompose {
  /**
   * `InitializeResult.capabilities.supportedOverrides` token: a held session (`stream/start`,
   * `interactive/start`, `recording/start`) plays `overrides.remoteCompose.documentBase64`. The
   * same spelling the Android daemon advertises
   * (`RobolectricHost.CARRIED_REMOTE_COMPOSE_LIVE_CAPABILITY`) — one token, one meaning, whichever
   * backend answers.
   */
  const val LIVE_CAPABILITY: String = "remoteCompose.documentBase64.live"

  /** The one player the desktop daemon draws a carried document with. */
  const val CMP_JVM: String = "cmp-jvm"

  /** Every spelling of [CMP_JVM] a request may use (rc-players' player-id table). */
  private val CMP_JVM_NAMES: Set<String> = setOf(CMP_JVM, "rcplayer-jvm")

  private const val BINDING_PACKAGE: String = "ee.schimke.composeai.daemon.rcplayer"

  private const val BINDING_CLASS: String = "$BINDING_PACKAGE.CmpJvmPlayerBinding"

  /** Placeholder class / function a [carriedDocumentSpec] names. Never loaded. */
  private const val CARRIED_DOCUMENT_CLASS: String = "ee.schimke.composeai.daemon.CarriedDocument"

  private const val CARRIED_DOCUMENT_FUNCTION: String = "Document"

  private val player: Result<CarriedDocumentPlayer> by lazy { runCatching { loadPlayer() } }

  /**
   * Whether this daemon can draw a carried document: the binding is in this jar and the player it
   * calls is on the classpath at a version it links against. What decides the capability tokens.
   */
  fun isAvailable(): Boolean = player.isSuccess

  /**
   * The carried document [overrides] name, or `null` when they carry none — which leaves the render
   * exactly what it was before carried documents reached the desktop daemon.
   *
   * Throws rather than drawing anything else: an [IllegalArgumentException] for bytes that are not
   * base64 or a player this daemon does not have, an [IllegalStateException] when the player is not
   * on the classpath. A caller that asked to see its document must never get a confident frame of
   * the preview instead.
   */
  fun resolve(overrides: PreviewOverrides?): CarriedRemoteComposeDocument? {
    val override = overrides?.remoteCompose ?: return null
    val entry = BundleIrReplayStore.carried(override) ?: return null
    requireDesktopPlayer(override)
    return CarriedRemoteComposeDocument(entry.bytes, override, player.getOrThrow())
  }

  /**
   * The base spec for a session or render on a [previewId] the manifest does not know, when
   * [overrides] carry a document to play instead — or `null` when they carry none, which keeps the
   * unknown-preview refusal. Size and density come from [overrides] on the merge that follows; the
   * class and function name nothing (see [CARRIED_DOCUMENT_CLASS]). Same shape as
   * `RobolectricHost.carriedDocumentSpec`.
   */
  fun carriedDocumentSpec(previewId: String, overrides: PreviewOverrides?): RenderSpec? {
    if (overrides?.remoteCompose?.documentBase64 == null) return null
    return RenderSpec(
      previewId = previewId,
      className = CARRIED_DOCUMENT_CLASS,
      functionName = CARRIED_DOCUMENT_FUNCTION,
      density = 1.0f,
      outputBaseName = "carried-document",
    )
  }

  /**
   * `playerId` (any spelling of [CMP_JVM]) or nothing at all. The `player` enum names only the two
   * AndroidX players, which have no desktop host, so it is refused like any other unknown player:
   * what draws is part of the answer, and swapping in the one player this daemon has would answer a
   * different question.
   */
  private fun requireDesktopPlayer(override: RemoteComposeOverride) {
    val playerId = override.playerId?.trim()?.takeIf { it.isNotEmpty() }
    if (playerId != null) {
      require(playerId.lowercase() in CMP_JVM_NAMES) {
        "The desktop daemon plays a carried Remote Compose document with '$CMP_JVM' only; " +
          "remoteCompose.playerId='$playerId' needs a daemon that has that player " +
          "(the Android daemon draws the AndroidX players and cmp-android)"
      }
      return
    }
    val kind = override.player
    require(kind == null) {
      "The desktop daemon plays a carried Remote Compose document with '$CMP_JVM' only; " +
        "remoteCompose.player=$kind names an AndroidX player, which runs on the Android daemon"
    }
  }

  private fun loadPlayer(): CarriedDocumentPlayer {
    val loader = CarriedRemoteCompose::class.java.classLoader
    val binding =
      try {
        Class.forName(BINDING_CLASS, false, loader)
      } catch (e: ClassNotFoundException) {
        throw IllegalStateException(
          "daemon-desktop was built without its Remote Compose player binding ($BINDING_CLASS)",
          e,
        )
      }
    val report = RemoteComposeLinkage.check(binding, listOf(BINDING_PACKAGE))
    // The linkage check reads the binding's bytecode wherever its jar or directory is; should it
    // find nothing to read, it passes vacuously. The player's entry point loading is the floor.
    val playerPresent = runCatching { Class.forName(PLAYER_ENTRY_CLASS, false, loader) }.isSuccess
    check(playerPresent && report.isLinked) {
      "overrides.remoteCompose.documentBase64 needs the CMP Remote Compose player " +
        "(ee.schimke.composeai:rc-player-compose-jvm) on the desktop daemon's classpath; " +
        if (!playerPresent) "$PLAYER_ENTRY_CLASS is not on it"
        else
          "${report.missing.size} reference(s) it does not provide: " +
            report.missing.take(MAX_LISTED).joinToString() +
            (if (report.missing.size > MAX_LISTED) ", …" else "")
    }
    return binding.getDeclaredConstructor().newInstance() as CarriedDocumentPlayer
  }

  private const val MAX_LISTED: Int = 8

  /** `RcComposePlayer`'s file class — the player's entry point. */
  private const val PLAYER_ENTRY_CLASS: String =
    "ee.schimke.composeai.rcplayer.compose.RcComposePlayerKt"
}
