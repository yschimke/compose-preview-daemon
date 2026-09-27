package ee.schimke.composeai.daemon

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
 * **The work trace.** The Android engine records each processor that ran in the render's metrics as
 * `postCapture.<kind>` = wall-clock ms, and each on-demand processor it skipped as
 * `postCaptureSkipped.<kind>` = 0. Metrics survive the sandbox classloader and worker-process hops
 * and surface in the `render/trace` data product's `metrics` object, so a client can assert which
 * processors a render paid for. The key prefixes are stable.
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
    kind !in ON_DEMAND_KINDS || requested == null || kind in requested

  /** The metrics key recording that [kind]'s processor ran. */
  public fun ranMetricKey(kind: String): String = RAN_METRIC_PREFIX + kind

  /** The metrics key recording that [kind]'s on-demand processor was skipped. */
  public fun skippedMetricKey(kind: String): String = SKIPPED_METRIC_PREFIX + kind

  /** Whether the render that produced [metrics] skipped [kind]'s processor. */
  public fun wasSkipped(metrics: Map<String, Long>?, kind: String): Boolean =
    metrics?.containsKey(skippedMetricKey(kind)) == true
}
