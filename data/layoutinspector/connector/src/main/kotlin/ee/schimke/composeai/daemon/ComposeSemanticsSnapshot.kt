package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsNode
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext

/** Semantics projections shared only within one captured frame, preserving unknown density. */
class ComposeSemanticsSnapshot
internal constructor(private val build: (Float?) -> ComposeSemanticsPayload) {
  constructor(
    root: SemanticsNode
  ) : this({ density -> ComposeSemanticsDataProducer.buildPayload(root, density) })

  private val payloads = mutableMapOf<Float?, ComposeSemanticsPayload>()

  // Null is distinct from 1f: consumers which did not request density must still omit that field.
  fun payload(density: Float?): ComposeSemanticsPayload =
    synchronized(payloads) { payloads.getOrPut(density) { build(density) } }
}

internal fun ExtensionPostCaptureContext.semanticsPayload(
  density: Float?
): ComposeSemanticsPayload =
  get(RenderArtifactContextKeys.SemanticsSnapshot)?.payload(density)
    ?: ComposeSemanticsDataProducer.buildPayload(
      require(RenderArtifactContextKeys.SemanticsRoot),
      density,
    )
