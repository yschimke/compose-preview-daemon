package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.bridge.InteractiveCommand

/**
 * Draws [content] inside a Wear widget carousel [slot], centred on the frame (#402), or [content]
 * unchanged when [slot] is null.
 *
 * The slot is clipped to its corner shape, as the carousel does, and handed to the widget as a
 * tight constraint: a widget player that fills its incoming constraints (`RemoteDocumentPreview`'s
 * `fillMaxSize`) then fills the slot, not the watch screen. The device's own round clip still
 * applies to the frame around it.
 */
@Composable
internal fun WearWidgetSlotFrame(slot: WearWidgetSlot?, content: @Composable () -> Unit) {
  if (slot == null) {
    content()
    return
  }
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(
      modifier =
        Modifier.requiredSize(slot.widthDp.dp, slot.heightDp.dp)
          .clip(RoundedCornerShape(slot.cornerRadiusDp.dp)),
      propagateMinConstraints = true,
    ) {
      content()
    }
  }
}

/** The slot [InteractiveCommand.Start] carries as primitives, or null when it carries none. */
internal fun InteractiveCommand.Start.wearWidgetSlot(): WearWidgetSlot? {
  val w = wearWidgetSlotWidthDp ?: return null
  val h = wearWidgetSlotHeightDp ?: return null
  return WearWidgetSlot(w, h, wearWidgetSlotCornerRadiusDp ?: 0f)
}
