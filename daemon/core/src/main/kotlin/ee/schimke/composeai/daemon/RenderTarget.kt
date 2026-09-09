package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a [RenderRequest.Render] is asking for — either a preview the backend still has to resolve,
 * or a spec that is already resolved.
 *
 * **Why this exists.** `JsonRpcServer` knows a `previewId` and a [PreviewOverrides]; it does not
 * know the `className` / `functionName` that a [RenderSpec] requires, because resolving those means
 * consulting the preview index (and, for a `@PreviewParameter` row, the consumer classpath) — which
 * on Android happens inside the Robolectric sandbox. So a render request is *unresolved* when it
 * leaves the JSON-RPC layer and *resolved* by the time it reaches a `RenderEngine`. Those are two
 * different shapes, and this type says so.
 *
 * They used to be the same shape: a single `payload: String` in the `;`-delimited `key=value`
 * grammar that `JsonRpcServer.renderTargetFor` wrote and each backend re-parsed. Twelve fields of
 * [PreviewOverrides] travelled as typed tokens, and **the rest of the object travelled as
 * base64-encoded JSON in an `overrides=` token beside them** — with the twelve nulled out of the
 * bag so they were not restated. Whether a field reached the renderer depended on whether someone
 * had remembered to add it to the encoder, and issue #3073 counted eight live fields that had not
 * been (`clockEpochMillis`, `placeholderActive`, `ambient`, `focus`, `keyboard`, `touchOverlay`,
 * `remoteCompose`, `launcherWidget`), after `permissions`, `gestures`, `lottie`, `namedOverrides`
 * and `themeProvider` had each been fixed the same way, one at a time, before it. The failure was
 * always silent: the protocol accepted the field, the merge applied it, the encoder dropped it, and
 * default pixels came back.
 *
 * Passing the object removes the question. A field added to [PreviewOverrides] reaches the renderer
 * because it is *on the object*, not because an encoder was taught about it.
 */
@Serializable
public sealed interface RenderTarget {

  /**
   * An unresolved request: render this discovery-time preview id, with these per-call overrides.
   *
   * [overrides] arrives with `device` already resolved into `widthPx` / `heightPx` / `density` by
   * [JsonRpcServer], because that resolution needs the device catalog and the precedence rules
   * PROTOCOL.md § 5 documents (an explicit `widthPx` outranks the device geometry, which outranks
   * the preview's own frame; `orientation` rotates a device-derived frame but not an explicitly
   * sized one). Backends read the resolved values and do not redo it.
   */
  @Serializable
  @SerialName("preview")
  public data class Preview(
    val previewId: String,
    val overrides: PreviewOverrides? = null,
    /** Data-product render mode (`a11y`, `theme`, …) implied by this preview's subscriptions. */
    val renderMode: String? = null,
    /**
     * Which `@PreviewParameter` row to bind, when the caller addressed one explicitly rather than
     * through a `<baseId>_<row>` preview id. An explicit value outranks the one parsed out of the
     * id, so a caller can render a row of the bare base id without minting a row id for it.
     */
    val previewParameterRow: String? = null,
  ) : RenderTarget

  /** A fully resolved spec: nothing left to look up, render exactly this. */
  @Serializable @SerialName("spec") public data class Spec(val spec: RenderSpec) : RenderTarget

  /**
   * A request carrying no renderable target at all — the host answers it from the render thread
   * without composing anything.
   *
   * This is the queue-plumbing lane: `DaemonHostTest`'s ten-render sandbox-reuse assertion submits
   * one of these and asserts on the classloader identity stamped into the [RenderResult], which is
   * the load-bearing daemon invariant (DESIGN.md § 9) and needs no preview to verify. Hosts answer
   * it with their stub render.
   *
   * Named rather than inferred: it used to be "a payload string that happens not to contain
   * `className=`", which meant any malformed real request silently became a stub render reported as
   * a success.
   */
  @Serializable @SerialName("stub") public data class Stub(val token: String = "") : RenderTarget

  /**
   * Run a classloader forensic dump instead of a render (CLASSLOADER-FORENSICS.md).
   *
   * It rides the render queue rather than a channel of its own because the dump is only meaningful
   * *inside* the sandbox classloader with the child loader active — exactly the state a real render
   * sees, which is the whole point of the daemon-path dump — and the render queue is what gets it
   * there. It used to ride as a render payload beginning `forensic-dump=`, told apart by a string
   * prefix; a variant says the same thing without the prefix being load-bearing.
   */
  @Serializable
  @SerialName("forensic")
  public data class Forensic(
    /** Absolute path to write the dump to. */
    val outPath: String,
    /** FQNs to survey for classloader identity. */
    val survey: List<String> = emptyList(),
  ) : RenderTarget

  public companion object {

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
      classDiscriminator = "target"
    }

    /**
     * Encode for one of the two boundaries that cannot pass a Kotlin object: the Robolectric
     * sandbox classloader crossing (`DaemonHostBridge`) and the sandbox worker-process hop
     * (SANDBOX-POOL.md). Everywhere else, pass the object.
     */
    public fun encode(target: RenderTarget): String = json.encodeToString(serializer(), target)

    /**
     * Inverse of [encode]. Throws on malformed input — a garbled boundary is a bug, not a degraded
     * mode to render through.
     */
    public fun decode(encoded: String): RenderTarget = json.decodeFromString(serializer(), encoded)
  }
}

/**
 * The previewId this target names, or `null` when it names none.
 *
 * The sandbox pool uses it as the affinity key so the same preview always lands on the same sandbox
 * — Compose snapshot caches and Robolectric shadow caches accumulate per-sandbox and pay off on
 * repeat renders (SANDBOX-POOL.md). A target with no previewId falls back to the request id, which
 * keeps a stable slot without the cache locality.
 */
public fun RenderTarget.previewIdOrNull(): String? =
  when (this) {
    is RenderTarget.Preview -> previewId.takeIf { it.isNotEmpty() }
    is RenderTarget.Spec -> spec.previewId?.takeIf { it.isNotEmpty() }
    is RenderTarget.Stub,
    is RenderTarget.Forensic -> null
  }
