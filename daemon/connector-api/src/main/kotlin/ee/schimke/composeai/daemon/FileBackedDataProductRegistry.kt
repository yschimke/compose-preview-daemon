package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Base for producers whose renderer writes one file per `(previewId, kind)` and whose registry
 * serves it back through `data/fetch` and `renderFinished` attachments. Subclasses usually supply
 * only [capabilities] and [fileFor]; subscription state and payloads synthesised from another
 * kind's file stay with the subclass.
 */
public abstract class FileBackedDataProductRegistry(
  final override val capabilities: List<DataProductCapability>,
  private val fileSystem: FileSystem = SystemFileSystem,
) : DataProductRegistry {

  private val byKind: Map<String, DataProductCapability> = capabilities.associateBy { it.kind }

  /**
   * The file backing `(previewId, kind)`; `null` means the kind is not ours ([Outcome.Unknown]).
   */
  protected abstract fun fileFor(previewId: String, kind: String): File?

  /**
   * Outcome for a missing file. Override to return [Outcome.RequiresRerender] for kinds whose
   * capability sets `requiresRerender`.
   */
  protected open fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    DataProductRegistry.Outcome.NotAvailable

  /** Decodes [file] as the inline payload; `null` is treated like a missing file. */
  protected open fun readInlinePayload(previewId: String, kind: String, file: File): JsonElement? =
    DEFAULT_JSON.parseToJsonElement(fileSystem.read(file.path.toPath()) { readUtf8() })

  /** Extras to attach beside the payload; [payload] is `null` for path transport. */
  protected open fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? = null

  /**
   * Whether `inline = true` may upgrade a non-`INLINE` [kind] to a read. Override to `false` for
   * kinds whose file is not JSON (e.g. a PNG overlay).
   */
  protected open fun allowInlineUpgrade(kind: String): Boolean = true

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val cap = byKind[kind] ?: return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId, kind) ?: return DataProductRegistry.Outcome.Unknown
    // `force` only means something for a `requiresRerender` kind (e.g. the per-preview SVG a
    // differently-overridden fetch must not reuse); elsewhere it would hide an existing file.
    if (!file.exists() || (cap.requiresRerender && forceRerender(params))) {
      return missingOutcome(previewId, kind)
    }
    val shouldInline =
      cap.transport == DataProductTransport.INLINE || (inline && allowInlineUpgrade(kind))
    return if (shouldInline) {
      val payload =
        try {
          readInlinePayload(previewId, kind, file)
        } catch (t: Throwable) {
          return DataProductRegistry.Outcome.FetchFailed(
            message = "could not parse $kind for $previewId: ${t.message}"
          )
        } ?: return missingOutcome(previewId, kind)
      DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = cap.schemaVersion,
          payload = payload,
          extras = extras(previewId, kind, payload),
        )
      )
    } else {
      DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = cap.schemaVersion,
          path = file.absolutePath,
          extras = extras(previewId, kind, payload = null),
        )
      )
    }
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    val out = mutableListOf<DataProductAttachment>()
    for (kind in kinds) {
      val cap = byKind[kind] ?: continue
      val file = fileFor(previewId, kind) ?: continue
      if (!file.exists()) continue
      when (cap.transport) {
        DataProductTransport.INLINE,
        DataProductTransport.BOTH -> {
          val payload =
            try {
              readInlinePayload(previewId, kind, file)
            } catch (t: Throwable) {
              System.err.println(
                "${this::class.simpleName}: parse $kind failed for $previewId: ${t.message}"
              )
              continue
            } ?: continue
          out +=
            DataProductAttachment(
              kind = kind,
              schemaVersion = cap.schemaVersion,
              payload = payload,
              extras = extras(previewId, kind, payload),
            )
        }
        DataProductTransport.PATH -> {
          out +=
            DataProductAttachment(
              kind = kind,
              schemaVersion = cap.schemaVersion,
              path = file.absolutePath,
              extras = extras(previewId, kind, payload = null),
            )
        }
      }
    }
    return out
  }

  private fun forceRerender(params: JsonElement?): Boolean =
    runCatching {
      params?.jsonObject?.get(DataFetchParams.PARAM_FORCE_RERENDER)?.jsonPrimitive?.booleanOrNull
    }
      .getOrNull() == true

  public companion object {
    private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
  }
}
