package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import kotlinx.serialization.json.Json

/**
 * Wire codec for a **carried** Remote Compose document (`overrides.remoteCompose.documentBase64`)
 * crossing into a held (interactive / live / recording) Android session — the whole
 * [RemoteComposeOverride] in, the same override out, with one `java.lang.String` in between.
 *
 * **Why a string.** The bridge command
 * ([ee.schimke.composeai.daemon.bridge.InteractiveCommand.Start]) may reference only `java.*` types
 * (see `DaemonHostBridge`'s file KDoc), and [RemoteComposeOverride] lives in the instrumented
 * `ee.schimke.composeai` namespace, which the sandbox re-loads. [encode] runs host-side with the
 * host's serializer, [decode] runs inside the sandbox with the sandbox's own copy, so the override
 * the held composition seeds `RemoteComposeController` with is minted by the classloader that reads
 * it — the same reason [HeldNamedOverrides] exists for the plain-Compose knobs.
 *
 * Only an override that carries a document crosses: [encode] answers `null` for every other bag, so
 * a live session on a preview of its own (a catalog preview, a bundle's IR replay) is planned
 * exactly as it was before carried documents reached the live lane.
 */
internal object HeldRemoteCompose {

  private val JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  /** Host-side: the override as JSON, or `null` when it carries no document. */
  fun encode(override: RemoteComposeOverride?): String? {
    if (override?.documentBase64 == null) return null
    return JSON.encodeToString(RemoteComposeOverride.serializer(), override)
  }

  /** Sandbox-side: back to the typed override, or `null` when nothing was carried. */
  fun decode(encoded: String?): RemoteComposeOverride? {
    if (encoded.isNullOrEmpty()) return null
    return JSON.decodeFromString(RemoteComposeOverride.serializer(), encoded)
  }
}
