package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import kotlinx.serialization.Serializable

/**
 * Marks a preview that draws a **Wear widget** (Glance Wear `WearWidgetPreview`, or a Remote
 * Compose document under the `wear-widgets` profile) and says which carousel slot it occupies.
 *
 * A Wear widget never owns the watch screen: the launcher lays it into a padded slot of the widget
 * carousel, sized by its `WearWidgetParams`. So when a caller forces such a preview onto a watch
 * `device` (`renderNow.overrides.device = "id:wearos_large_round"`, a `render_matrix` device
 * sweep), the renderer draws it inside [slotFor]'s bounds centred on the device frame, rather than
 * across the whole screen under the round clip (#402). With no watch device on the spec there is
 * nothing to centre in, and the preview renders exactly as it did before this marker existed.
 *
 * Read off the discovery manifest's entry-level `widget` object (`{"host": "wear", …}`), which
 * compose-ai-tools has written since it began classifying widget previews; see
 * `PreviewManifestEntry.wearWidget` in the Android router.
 *
 * @property shape the slot's corner treatment.
 * @property container whether the widget takes a small (one-row) or large (two-row) slot.
 * @property slotWidthDp explicit slot width, when the manifest states one; null derives it from
 *   [shape], [container] and the device.
 * @property slotHeightDp explicit slot height; see [slotWidthDp]. Both must be set to take effect.
 * @property slotCornerRadiusDp explicit corner radius; null takes [shape]'s.
 */
@Serializable
public data class WearWidgetSpec(
  val shape: Shape = Shape.SQUIRCLE,
  val container: Container = Container.LARGE,
  val slotWidthDp: Float? = null,
  val slotHeightDp: Float? = null,
  val slotCornerRadiusDp: Float? = null,
) {

  /** The three slot shapes `androidx.glance.wear.tooling.preview` ships providers for. */
  public enum class Shape(public val cornerRadiusDp: Float) {
    SQUIRCLE(26f),
    /** A pill: Compose clamps the radius to half the slot's shorter edge. */
    ROUND(999f),
    RECTANGULAR(0f),
  }

  public enum class Container {
    SMALL,
    LARGE,
  }

  /**
   * The slot this widget occupies on [device], or null when [device] is not a watch — in which case
   * the renderer leaves the preview alone.
   *
   * Sizes are glance-wear's own preview params (`WidgetPreviewParams.kt`, `1.0.0-alpha19`): each
   * shape × container has one entry for a small screen and one for a large one, and Wear's
   * large-screen breakpoint is 225dp.
   */
  public fun slotFor(device: String?): WearWidgetSlot? {
    if (device.isNullOrBlank()) return null
    val screen = DeviceDimensions.resolve(device)
    if (!screen.isRound && !device.contains("wear", ignoreCase = true)) return null
    val radius = slotCornerRadiusDp ?: shape.cornerRadiusDp
    if (slotWidthDp != null && slotHeightDp != null && slotWidthDp > 0f && slotHeightDp > 0f) {
      return WearWidgetSlot(slotWidthDp, slotHeightDp, radius)
    }
    val large = screen.widthDp >= LARGE_SCREEN_BREAKPOINT_DP
    val (w, h) = defaultSlotDp(shape, container, large)
    return WearWidgetSlot(w, h, radius)
  }

  public companion object {
    /** Wear's large-screen width breakpoint, in dp. */
    public const val LARGE_SCREEN_BREAKPOINT_DP: Int = 225

    /** `PreviewWidget.host` for a Wear widget, as compose-ai-tools discovery writes it. */
    public const val HOST_WEAR: String = "wear"

    private const val TOOLING_PREVIEW_PACKAGE = "androidx.glance.wear.tooling.preview."

    /**
     * The slot shape and container named by a glance-wear `@PreviewParameter` provider
     * (`SquircleSmallWidgetPreviewParams`, `RoundLargeWidgetPreviewParams`, …). An `…All…`
     * provider, an unknown one, or none at all takes the default large squircle — what a round
     * watch's carousel shows.
     */
    public fun fromProvider(
      providerClassName: String?,
      slotWidthDp: Float? = null,
      slotHeightDp: Float? = null,
      slotCornerRadiusDp: Float? = null,
    ): WearWidgetSpec {
      val simple =
        providerClassName
          ?.takeIf { it.startsWith(TOOLING_PREVIEW_PACKAGE) }
          ?.substringAfterLast('.') ?: ""
      val shape =
        when {
          simple.startsWith("Round") -> Shape.ROUND
          simple.startsWith("Rectangular") -> Shape.RECTANGULAR
          else -> Shape.SQUIRCLE
        }
      val container = if (simple.contains("Small")) Container.SMALL else Container.LARGE
      return WearWidgetSpec(shape, container, slotWidthDp, slotHeightDp, slotCornerRadiusDp)
    }

    private fun defaultSlotDp(
      shape: Shape,
      container: Container,
      large: Boolean,
    ): Pair<Float, Float> =
      when (shape) {
        Shape.SQUIRCLE ->
          when (container) {
            Container.SMALL -> if (large) 200f to 60f else 166f to 60f
            Container.LARGE -> if (large) 200f to 108f else 166f to 96f
          }
        Shape.ROUND ->
          when (container) {
            Container.SMALL -> if (large) 200f to 60f else 182f to 54f
            Container.LARGE -> if (large) 160f to 136f else 150f to 120f
          }
        // glance-wear ships one rectangular entry per container, for square watches.
        Shape.RECTANGULAR ->
          when (container) {
            Container.SMALL -> 192f to 60f
            Container.LARGE -> 168f to 112f
          }
      }
  }
}

/** A resolved Wear widget slot, in dp, centred on the device frame by the renderer. */
@Serializable
public data class WearWidgetSlot(
  val widthDp: Float,
  val heightDp: Float,
  val cornerRadiusDp: Float,
)
