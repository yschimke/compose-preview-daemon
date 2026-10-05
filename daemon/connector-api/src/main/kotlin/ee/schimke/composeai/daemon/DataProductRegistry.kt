package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewContext
import kotlinx.serialization.json.JsonElement

/**
 * Producer-side seam for data products (docs/daemon/DATA-PRODUCTS.md). [JsonRpcServer] builds no
 * payload itself; it routes `data/fetch`, `data/subscribe`, `data/unsubscribe` and `renderFinished`
 * attachments through this registry. Producers are registered by `DaemonMain`.
 */
public interface DataProductRegistry {
  /** Kinds the daemon can produce; surfaced as `initialize.capabilities.dataProducts`. */
  public val capabilities: List<DataProductCapability>

  /** Whether [kind] is advertised; lets subscribe reject unknown kinds before any bookkeeping. */
  public fun isKnown(kind: String): Boolean = capabilities.any { it.kind == kind }

  /**
   * Fetches `(previewId, kind)` from the latest render. [params] are per-kind options (e.g.
   * `nodeId`); [inline] asks for the payload inline rather than as a `path`.
   */
  public fun fetch(previewId: String, kind: String, params: JsonElement?, inline: Boolean): Outcome

  /**
   * Attachments for [previewId]'s `renderFinished` across [kinds] (subscriptions plus the global
   * attach set). Called after the render wrote its outputs; kinds with nothing to offer drop out.
   */
  public fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment>

  /**
   * Called after a render and before attachments are collected, so producers can snapshot the
   * result. The only overridable render hook; the narrower [onRender] extensions forward here.
   */
  public fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {}

  /**
   * Render failure lifecycle hook. Called before `renderFailed` is emitted so failure-oriented
   * producers can snapshot the throwable for a later `data/fetch`.
   */
  public fun onRenderFailed(previewId: String, cause: Throwable) {}

  /**
   * Called on each successful `data/subscribe`, including a re-subscribe (a producer's reset
   * signal). [params] is the per-kind option bag, e.g. `{ frameStreamId, mode }`.
   */
  public fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {}

  /**
   * Called on `data/unsubscribe`, when [previewId] leaves the visible set, and at shutdown; clear
   * per-subscription state here.
   */
  public fun onUnsubscribe(previewId: String, kind: String) {}

  /**
   * Render mode (e.g. `"a11y"`) a subscription to [kind] requires, or `null` for the standard
   * pipeline. See [JsonRpcServer.subscriptionDrivenRenderMode].
   */
  public fun renderModeFor(kind: String): String? = null

  /** Outcome of a [fetch]; each failure maps to its wire error. */
  public sealed interface Outcome {
    public data class Ok(val result: DataFetchResult) : Outcome

    /** Kind not advertised by this registry → `DataProductUnknown` (-32020). */
    public data object Unknown : Outcome

    /** Preview has never rendered → `DataProductNotAvailable` (-32021). */
    public data object NotAvailable : Outcome

    /** Producer-side failure → `DataProductFetchFailed` (-32022). */
    public data class FetchFailed(val message: String, val errorKind: String? = null) : Outcome

    /** Re-render budget tripped → `DataProductBudgetExceeded` (-32023). */
    public data object BudgetExceeded : Outcome

    /**
     * The kind needs a fresh render in [mode] (DATA-PRODUCTS.md § "Re-render semantics"). The
     * dispatcher re-renders, waits up to `dataFetchRerenderBudgetMs` (else [BudgetExceeded],
     * without cancelling the render), then calls [fetch] again. Pick the smallest mode that
     * produces the kind.
     */
    public data class RequiresRerender(val mode: String) : Outcome
  }

  public companion object {
    /** Advertises no kinds; every fetch is [Outcome.Unknown]. For tests and fake hosts. */
    public val Empty: DataProductRegistry =
      object : DataProductRegistry {
        override val capabilities: List<DataProductCapability> = emptyList()

        override fun fetch(
          previewId: String,
          kind: String,
          params: JsonElement?,
          inline: Boolean,
        ): Outcome = Outcome.Unknown

        override fun attachmentsFor(
          previewId: String,
          kinds: Set<String>,
        ): List<DataProductAttachment> = emptyList()
      }
  }
}

/**
 * Narrow [DataProductRegistry.onRender] call shapes. Extensions, not members, so no producer can
 * override one and silently miss renders; `previewContext` comes from
 * [RenderResult.previewContext].
 */
public fun DataProductRegistry.onRender(previewId: String, result: RenderResult) {
  onRender(previewId, result, overrides = null, previewContext = result.previewContext)
}

/** @see onRender */
public fun DataProductRegistry.onRender(
  previewId: String,
  result: RenderResult,
  overrides: PreviewOverrides?,
) {
  onRender(previewId, result, overrides, previewContext = result.previewContext)
}
