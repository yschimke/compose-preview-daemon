package ee.schimke.composeai.daemon

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.semantics.SemanticsNode
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext

/** One lazy layout walk shared by the data products for a single captured frame. */
class LayoutInspectorSnapshot internal constructor(build: () -> LayoutInspectorPayload?) {
  constructor(
    root: SemanticsNode,
    slotTables: List<CompositionData>,
    density: Float,
    fontScale: Float,
  ) : this({ LayoutInspectorDataProducer.buildPayload(root, slotTables, density, fontScale) })

  // A successful initialization drops its builder (and its references to Compose's live tree).
  // This object belongs to one post-capture context; it must never be stored on an extension.
  val payload: LayoutInspectorPayload? by lazy(build)
}

internal fun ExtensionPostCaptureContext.layoutInspectorPayload(): LayoutInspectorPayload? {
  val snapshot = get(RenderArtifactContextKeys.LayoutSnapshot)
  if (snapshot != null) return snapshot.payload
  // Standalone extension callers can still provide the original primitive context keys.
  return LayoutInspectorDataProducer.buildPayload(
    require(RenderArtifactContextKeys.SemanticsRoot),
    get(RenderArtifactContextKeys.SlotTables).orEmpty(),
    get(RenderArtifactContextKeys.Density) ?: 1f,
    get(RenderArtifactContextKeys.FontScale) ?: 1f,
  )
}
