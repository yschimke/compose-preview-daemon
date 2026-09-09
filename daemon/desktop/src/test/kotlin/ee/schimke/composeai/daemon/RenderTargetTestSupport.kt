package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides

/**
 * An unresolved render target naming [previewId], the shape `JsonRpcServer` hands a host.
 *
 * Test sugar only: these call sites used to build a `;`-delimited `previewId=…;widthPx=…` payload
 * string, which is exactly the encoding this refactor removed. Keeping a one-line builder here
 * means a test reads as "render this preview with these overrides" without every file restating the
 * constructor.
 */
internal fun preview(previewId: String, overrides: PreviewOverrides? = null): RenderTarget.Preview =
  RenderTarget.Preview(previewId = previewId, overrides = overrides)
