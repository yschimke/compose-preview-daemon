package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties

/**
 * Which post-capture processors a render runs, and the per-render record of what it ran.
 *
 * Most always-on data extensions are cheap next to the capture, so they run on every render and a
 * later `data/fetch` finds their file on disk. A few are not: the `compose/figma-svg` layered
 * export costs several seconds per render (compose-preview-server#1174), which dominated the warm
 * edit-render loop even though nothing had subscribed to it. Those kinds are **on demand**: a
 * render runs one only when its kind is in [RenderSpec.requestedDataKinds], or when that set is
 * `null` (a caller that predates the gate).
 *
 * **The work trace.** Each engine records each processor that ran in the render's metrics as
 * `postCapture.<kind>` = wall-clock ms, and each on-demand processor it skipped as
 * `postCaptureSkipped.<kind>` = 0. Metrics survive the sandbox classloader and worker-process hops
 * and surface in the `render/trace` data product's `metrics` object, so a client can assert which
 * processors a render paid for. The key prefixes are stable. [RenderWorkTraceMetrics] projects the
 * same keys onto `renderFinished.workTrace`.
 *
 * **The allowlist.** A batch client that reads a fixed set of sidecars off disk (the CLI's `bundle
 * pack --with-semantics`) can set [DaemonProperties.postCaptureKinds] to name them. Every
 * post-capture processor then becomes on demand: it runs when its kind is on the allowlist or in
 * [RenderSpec.requestedDataKinds], and is skipped (and recorded as skipped) otherwise. Unset, each
 * engine keeps its own default — so a client that names nothing loses nothing.
 */
public object PostCaptureGate {

  /** Metrics key prefix for a post-capture processor that ran; the value is its wall-clock ms. */
  public const val RAN_METRIC_PREFIX: String = "postCapture."

  /** Metrics key prefix for an on-demand processor the render skipped; the value is `0`. */
  public const val SKIPPED_METRIC_PREFIX: String = "postCaptureSkipped."

  /** Kinds whose post-capture processor runs only when requested. */
  public val ON_DEMAND_KINDS: Set<String> = setOf("compose/figma-svg")

  /** Whether the processor producing [kind] should run for a render requesting [requested]. */
  public fun shouldRun(kind: String, requested: Set<String>?): Boolean =
    shouldRun(kind, requested, onDemandKinds = ON_DEMAND_KINDS, allowlist = null)

  /**
   * Whether the processor producing [kind] should run for a render requesting [requested].
   *
   * Without an [allowlist], a kind in [onDemandKinds] runs only when requested (or when [requested]
   * is `null`, a caller that predates the gate) and every other kind always runs. With one, every
   * processor is on demand: it runs only when one of the kinds it writes ([kindsWrittenBy]) is on
   * the allowlist or in [requested].
   */
  public fun shouldRun(
    kind: String,
    requested: Set<String>?,
    onDemandKinds: Set<String>,
    allowlist: Set<String>?,
  ): Boolean {
    if (allowlist != null) {
      return kindsWrittenBy(kind).any { it in allowlist || (requested != null && it in requested) }
    }
    return kind !in onDemandKinds || requested == null || kind in requested
  }

  /**
   * The kinds the processor with id [kind] writes: its own, plus any sidecar it produces alongside.
   * The semantics-wireframe processor also writes `compose/spatial-semantics`, so asking for either
   * runs it.
   */
  public fun kindsWrittenBy(kind: String): Set<String> =
    setOf(kind) + EXTRA_KINDS_WRITTEN[kind].orEmpty()

  /**
   * [DaemonProperties.postCaptureKinds] as a set, or `null` when unset (no allowlist). Read per
   * render, so a test can toggle it; the lookup is a single `System.getProperty`.
   */
  public fun allowlistFromSystemProperties(): Set<String>? =
    DaemonProperties.postCaptureKinds.read().toSet().takeIf { it.isNotEmpty() }

  private val EXTRA_KINDS_WRITTEN: Map<String, Set<String>> =
    mapOf("compose/semantics-wireframe" to setOf("compose/spatial-semantics"))

  /** The metrics key recording that [kind]'s processor ran. */
  public fun ranMetricKey(kind: String): String = RAN_METRIC_PREFIX + kind

  /** The metrics key recording that [kind]'s on-demand processor was skipped. */
  public fun skippedMetricKey(kind: String): String = SKIPPED_METRIC_PREFIX + kind

  /** Whether the render that produced [metrics] skipped [kind]'s processor. */
  public fun wasSkipped(metrics: Map<String, Long>?, kind: String): Boolean =
    metrics?.containsKey(skippedMetricKey(kind)) == true
}
