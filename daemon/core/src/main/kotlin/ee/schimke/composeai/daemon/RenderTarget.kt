package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a [RenderRequest.Render] asks for. `JsonRpcServer` knows only a previewId and
 * [PreviewOverrides]; resolving the [RenderSpec] needs the preview index (and, on Android, the
 * sandbox), so a request is *unresolved* when it leaves the JSON-RPC layer and *resolved* when it
 * reaches a `RenderEngine`.
 *
 * Overrides travel as the whole object so a new [PreviewOverrides] field reaches the renderer
 * without anyone teaching an encoder about it (issue #3073).
 */
@Serializable
public sealed interface RenderTarget {

  /**
   * An unresolved request for a discovery-time preview id. [JsonRpcServer] has already resolved
   * `device` into `widthPx` / `heightPx` / `density` (PROTOCOL.md § 5); backends must not redo it.
   */
  @Serializable
  @SerialName("preview")
  public data class Preview(
    val previewId: String,
    val overrides: PreviewOverrides? = null,
    /** Data-product render mode (`a11y`, `theme`, …) implied by this preview's subscriptions. */
    val renderMode: String? = null,
    /** Explicit `@PreviewParameter` row; outranks a row parsed from a `<baseId>_<row>` id. */
    val previewParameterRow: String? = null,
    /**
     * Data-product kinds requested for this render; resolved onto [RenderSpec.requestedDataKinds].
     * `null` runs every post-capture processor (see there).
     */
    val dataKinds: Set<String>? = null,
  ) : RenderTarget

  /** A fully resolved spec: nothing left to look up, render exactly this. */
  @Serializable @SerialName("spec") public data class Spec(val spec: RenderSpec) : RenderTarget

  /**
   * No renderable target: the host answers with its stub render. Exercises queue plumbing and
   * sandbox reuse (DESIGN.md § 9) without a preview.
   */
  @Serializable @SerialName("stub") public data class Stub(val token: String = "") : RenderTarget

  /**
   * A classloader forensic dump instead of a render (CLASSLOADER-FORENSICS.md). Rides the render
   * queue because the dump only means something in the sandbox state a real render sees.
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

    /** For the sandbox classloader and worker-process crossings only; elsewhere pass the object. */
    public fun encode(target: RenderTarget): String = json.encodeToString(serializer(), target)

    /** Inverse of [encode]. Throws on malformed input: a garbled boundary is a bug. */
    public fun decode(encoded: String): RenderTarget = json.decodeFromString(serializer(), encoded)
  }
}

/**
 * The previewId this target names, or `null`. The sandbox pool's affinity key, so repeat renders of
 * a preview reuse one sandbox's warm caches (SANDBOX-POOL.md).
 */
public fun RenderTarget.previewIdOrNull(): String? =
  when (this) {
    is RenderTarget.Preview -> previewId.takeIf { it.isNotEmpty() }
    is RenderTarget.Spec -> spec.previewId?.takeIf { it.isNotEmpty() }
    is RenderTarget.Stub,
    is RenderTarget.Forensic -> null
  }
