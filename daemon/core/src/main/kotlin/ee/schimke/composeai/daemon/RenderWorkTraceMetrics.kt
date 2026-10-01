package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderWorkTrace

/**
 * The per-render work trace — which post-capture processors ran, which data-product kinds were
 * computed, and how long each step took — and its projection onto `renderFinished.workTrace`.
 *
 * **Why it travels in [RenderResult.metrics].** A render result reaches [JsonRpcServer] across up
 * to three hops: the Robolectric sandbox classloader (copied reflectively, field by field), the
 * sandbox-worker process (serialised as `RenderResultDto`), and the interactive session's own copy.
 * The flat metrics map already survives all of them untouched and in insertion order, and it is
 * where [PostCaptureGate] records each processor that ran (`postCapture.<id>` = ms). So the engines
 * record the rest of the trace there too, under the stable keys below, and [fromMetrics] rebuilds
 * the typed wire value at the one place `renderFinished` is built. No hop needs to learn a new
 * field, and a result whose engine records nothing simply carries no trace.
 *
 * The keys:
 * - [RECORDED_METRIC_KEY] — present (value `1`) when the engine recorded a trace at all. It is what
 *   tells "recorded, nothing ran" (empty lists on the wire) apart from "not recorded" (no
 *   `workTrace`): hosts that never record — the harness fake, the scroll / GIF / Lottie modes —
 *   emit no trace rather than a misleadingly empty one.
 * - `postCapture.<id>` ([PostCaptureGate.ranMetricKey]) — a post-capture processor ran, in order;
 *   the value is its wall-clock ms. A processor's id is also the data kind it computes.
 * - `dataKind.<kind>` ([dataKindMetricKey]) — a data kind computed outside the post-capture
 *   processor loop (the a11y, `uia/hierarchy` and display-filter producers); the value is the wall
 *   time of the step that computed it. A step that computes several kinds reports its wall time
 *   against each.
 */
public object RenderWorkTraceMetrics {

  /** Metrics key whose presence marks a result as carrying a recorded work trace. */
  public const val RECORDED_METRIC_KEY: String = "workTrace.recorded"

  /** Metrics key prefix for a data kind computed outside the post-capture processor loop. */
  public const val DATA_KIND_METRIC_PREFIX: String = "dataKind."

  /** The metrics key recording that [kind] was computed by a non-processor step. */
  public fun dataKindMetricKey(kind: String): String = DATA_KIND_METRIC_PREFIX + kind

  /**
   * Marks [metrics] as carrying a recorded work trace. Call once per render that records one,
   * before any step runs, so a render that runs nothing still reports empty lists.
   */
  public fun markRecorded(metrics: MutableMap<String, Long>) {
    metrics[RECORDED_METRIC_KEY] = 1L
  }

  /**
   * Records into [metrics] that a step started at [startNs] (a [System.nanoTime] reading) computed
   * [kinds], each against the step's wall time up to now.
   */
  public fun recordDataKinds(
    metrics: MutableMap<String, Long>,
    kinds: Collection<String>,
    startNs: Long,
  ) {
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    for (kind in kinds) metrics[dataKindMetricKey(kind)] = tookMs
  }

  /**
   * Rebuilds the [RenderWorkTrace] the engine recorded into [metrics], or `null` when it recorded
   * none (no [RECORDED_METRIC_KEY]). Lists keep the metrics map's insertion order, which is the
   * order the work ran.
   */
  public fun fromMetrics(metrics: Map<String, Long>?): RenderWorkTrace? {
    if (metrics == null || RECORDED_METRIC_KEY !in metrics) return null
    val processors = mutableListOf<String>()
    val dataKinds = LinkedHashSet<String>()
    val stepMs = LinkedHashMap<String, Long>()
    for ((key, value) in metrics) {
      when {
        key.startsWith(PostCaptureGate.RAN_METRIC_PREFIX) -> {
          val id = key.removePrefix(PostCaptureGate.RAN_METRIC_PREFIX)
          processors += id
          dataKinds += id
          stepMs[id] = value
        }
        key.startsWith(DATA_KIND_METRIC_PREFIX) -> {
          val kind = key.removePrefix(DATA_KIND_METRIC_PREFIX)
          dataKinds += kind
          stepMs[kind] = value
        }
      }
    }
    return RenderWorkTrace.Builder()
      .also {
        it.processors = processors.toList()
        it.dataKinds = dataKinds.toList()
        it.stepMs = stepMs.toMap()
      }
      .build()
  }
}
