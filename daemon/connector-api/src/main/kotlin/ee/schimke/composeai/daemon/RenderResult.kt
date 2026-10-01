package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.RenderTrace

/**
 * Result of a single render. Backend-agnostic shape — fields are protocol concerns (id, classloader
 * identity for diagnostics), not Robolectric- or Compose-specific.
 *
 * The `classLoaderHashCode`/`classLoaderName` pair lets host-internal tests verify that long-lived
 * backends genuinely reuse a single sandbox / classloader across renders (DESIGN.md § 9 — the
 * load-bearing daemon invariant).
 *
 * `artifact` and `metrics` are populated by hosts that actually render bytes (`FakeHost` in
 * `:daemon:harness`, `DesktopHost`/`RobolectricHost` once their B1.4 render-engine bodies land).
 * They map directly onto the [`renderFinished`](../../docs/daemon/PROTOCOL.md#renderfinished) wire
 * shape: `artifact` is projected to `renderFinished.pngPath` via `pathOrNull()`; `metrics` becomes
 * a flat `renderFinished.metrics` (numeric counters; the structured `RenderMetrics` shape is filled
 * in by B2.3 once the daemon tracks heap / sandbox-age etc.). Both default to `null` so the
 * B1.5-era stub paths in `JsonRpcServer.renderFinishedFromResult` keep emitting the placeholder
 * `daemon-stub-${id}.png`.
 *
 * [previewContext] carries the effective render metadata and optional inspection captures that data
 * products can project from after the render completes.
 *
 * [outputBaseName] is the concrete artifact identity selected by the renderer. It can differ from
 * the protocol preview id when one discovered function expands into multiple annotation or catalog
 * variants; file-backed products use it to resolve the artifact produced by this exact render.
 *
 * [trace] carries the phase timings the engine's own `trace.section(...)` calls recorded — what
 * `render/trace` v2 reports. It rides on the result rather than through a process-global the
 * registry reads, because the recorder is created per render deep inside the engine and the result
 * is the channel that already runs from there to the daemon. Null from hosts that don't run a
 * recorder (the harness fake, sandbox-worker replies), which is exactly when `render/trace` falls
 * back to its v1 single-phase shape over `metrics["tookMs"]`.
 */
public data class RenderResult(
  val id: Long,
  val classLoaderHashCode: Int,
  val classLoaderName: String,
  /**
   * What the render produced. See [RenderArtifact] for why this is a value rather than the
   * `pngPath: String?` it replaced — in short, that field was not always a PNG, not always a path
   * that existed, and was re-read off disk on every `renderFinished` to get back the bytes the
   * backend had just written.
   */
  val artifact: RenderArtifact? = null,
  val metrics: Map<String, Long>? = null,
  val previewContext: PreviewContext? = null,
  val outputBaseName: String? = null,
  val trace: RenderTrace? = null,
) {

  /**
   * [artifact] as JSON, for the Robolectric sandbox classloader crossing.
   *
   * A property rather than a call at the crossing because the host side reads it **reflectively** —
   * `getArtifactJson` — having matched this class by name rather than by identity. `RenderResult`
   * lives in the instrumented `ee.schimke.composeai.daemon` package, so the sandbox's copy is a
   * different `Class` object and only a `String` survives intact. The mirror image of
   * `RenderRequest.Render.targetJson` on the way in.
   */
  public val artifactJson: String?
    get() = artifact?.let(RenderArtifact::encode)
}
