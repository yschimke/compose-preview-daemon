package ee.schimke.composeai.daemon.history

/**
 * Filter for [HistorySource.list], mirroring `history/list` params (HISTORY.md § "Layer 2 —
 * JSON-RPC API"). Every field is optional. `since` / `until` compare ISO-8601 UTC strings
 * lexically; `branch` is exact and [branchPattern] a regex.
 */
public data class HistoryFilter(
  val previewId: String? = null,
  val since: String? = null,
  val until: String? = null,
  val limit: Int? = null,
  val cursor: String? = null,
  val branch: String? = null,
  val branchPattern: String? = null,
  val commit: String? = null,
  val worktreePath: String? = null,
  val agentId: String? = null,
  val sourceKind: String? = null,
  val sourceId: String? = null,
  /**
   * Serve the listing from an on-demand [GitRefHistorySource] for this full ref name instead of the
   * configured sources. Routing only; [HistoryFilters.matches] ignores it.
   */
  val ref: String? = null,
)

/**
 * One newest-first page from [HistorySource.list]. [totalCount] counts all matches before
 * pagination; [nextCursor] is set when more remain.
 */
public data class HistoryListPage(
  val entries: List<HistoryEntry>,
  val nextCursor: String? = null,
  val totalCount: Int,
)

/** Result of [HistorySource.read]; [pngBytes] only when inline bytes were requested. */
public data class HistoryReadResult(
  val entry: HistoryEntry,
  val previewMetadata: PreviewMetadataSnapshot?,
  val pngPath: String,
  val pngBytes: ByteArray? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is HistoryReadResult) return false
    if (entry != other.entry) return false
    if (previewMetadata != other.previewMetadata) return false
    if (pngPath != other.pngPath) return false
    if (pngBytes == null) return other.pngBytes == null
    if (other.pngBytes == null) return false
    return pngBytes.contentEquals(other.pngBytes)
  }

  override fun hashCode(): Int {
    var result = entry.hashCode()
    result = 31 * result + (previewMetadata?.hashCode() ?: 0)
    result = 31 * result + pngPath.hashCode()
    result = 31 * result + (pngBytes?.contentHashCode() ?: 0)
    return result
  }
}

/**
 * Outcome of [HistorySource.write]. `SKIPPED_DUPLICATE` means the bytes equal the *most recent*
 * entry for the preview, so nothing was written and no `historyAdded` fires: A → A is skipped, but
 * A → B → A keeps the return to A.
 */
public enum class WriteResult {
  WRITTEN,
  SKIPPED_DUPLICATE,
}

/**
 * Pluggable history backend (HISTORY.md § "HistorySource interface"): [LocalFsHistorySource] reads
 * and writes, [GitRefHistorySource] exposes reporting refs read-only.
 */
public interface HistorySource {
  /** Stable identifier — e.g. `"fs:/abs/historyDir"`, `"git:preview/main"`, `"http:https://…"`. */
  public val id: String

  /** `kind` from [HistorySourceInfo] — `"fs"`, `"git"`, `"http"`. */
  public val kind: String

  /** True when this source can accept [write] calls. FS=true; git/HTTP read-only sources=false. */
  public fun supportsWrites(): Boolean

  /**
   * Persists [entry] and its PNG; throws when not [supportsWrites]. Failures are logged by the
   * caller and never fail the render.
   */
  public fun write(entry: HistoryEntry, png: ByteArray): WriteResult

  /** Lists history entries newest-first, applying [filter] and paginating. */
  public fun list(filter: HistoryFilter): HistoryListPage

  /** Reads one entry by id. Returns null when the id isn't present in this source. */
  public fun read(entryId: String, includeBytes: Boolean = false): HistoryReadResult?

  /**
   * Applies [config] in order (HISTORY.md § "Pruning policy"): age, then per-preview count, then
   * total size, each on the previous pass's survivors. A knob `<= 0` disables its pass, and the
   * newest entry per preview is never dropped. [dryRun] reports without deleting. Read-only sources
   * return [PruneResult.EMPTY].
   */
  public fun prune(config: HistoryPruneConfig, dryRun: Boolean = false): PruneResult =
    PruneResult.EMPTY

  /** Flushes pending work (e.g. a debounced git commit batch) and releases resources. */
  public fun close() {}
}
