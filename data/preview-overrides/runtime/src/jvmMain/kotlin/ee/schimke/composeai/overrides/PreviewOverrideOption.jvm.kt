package ee.schimke.composeai.overrides

/**
 * The JVM binding of the common [PreviewOverrideOption]: the *existing* serializable wire shape
 * from `:data-preview-overrides-core`.
 *
 * An `actual typealias` rather than a fresh class, so every JVM consumer that already imports
 * `ee.schimke.composeai.data.overrides.PreviewOverrideOption` — the connector, the producer, a
 * preview calling `previewOverrideChoice(options = …)` — keeps compiling and keeps linking against
 * the same type. The multiplatform split adds a common *name*; it does not add a JVM type.
 */
actual typealias PreviewOverrideOption = ee.schimke.composeai.data.overrides.PreviewOverrideOption

actual fun previewOverrideOption(value: String, label: String): PreviewOverrideOption =
  ee.schimke.composeai.data.overrides.PreviewOverrideOption(value, label)

/** JVM default: the process-static controller-backed host. */
actual val DefaultPreviewOverrideHost: PreviewOverrideHost = ControllerPreviewOverrideHost
