package benchmark.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** App-owned public getters exercise successful metadata entries during real reloads. */
class ReloadCornerShape : Shape {
  val topStart: CornerSize = CornerSize(6.dp)
  val topEnd: CornerSize = topStart
  val bottomEnd: CornerSize = topStart
  val bottomStart: CornerSize = topStart

  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline = RoundedCornerShape(6.dp).createOutline(size, layoutDirection, density)
}

/** No corner getters or shape fields: exercises cached misses on an app-owned class. */
class ReloadOpaqueShape : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
}

@Composable
fun ReloadCustomShapeDashboardPreview() {
  for (type in listOf(ReloadCornerShape::class.java, ReloadOpaqueShape::class.java)) {
    check(type.classLoader.javaClass.name.endsWith("ChildFirstURLClassLoader")) {
      "Custom shape fixture must be loaded by the application child classloader"
    }
  }
  Column(Modifier.fillMaxSize()) {
    Row {
      Box(
        Modifier.size(24.dp)
          .testTag("reload-corner-shape")
          .background(Color.Blue, ReloadCornerShape())
      )
      Box(
        Modifier.size(24.dp)
          .testTag("reload-opaque-shape")
          .background(Color.Red, ReloadOpaqueShape())
      )
    }
    Box(Modifier.weight(1f)) { ReloadDashboardPreview() }
  }
}
