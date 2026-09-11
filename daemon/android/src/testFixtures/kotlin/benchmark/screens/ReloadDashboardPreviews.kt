package benchmark.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object ReloadAnchor

/**
 * Application-classloader churn fixture. Keep the composition in this package: delegating to the
 * daemon's fixtures would put its classes on the parent-first list and miss actual unloading.
 */
@Composable
fun ReloadDashboardPreview() {
  check(ReloadAnchor::class.java.classLoader.javaClass.name.endsWith("ChildFirstURLClassLoader")) {
    "Reload fixture must be loaded by the application child classloader"
  }
  MaterialTheme {
    Surface(Modifier.fillMaxSize()) {
      Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text("Service overview", style = MaterialTheme.typography.headlineSmall)
        Text("24 services · regional health and request volume")
        repeat(12) { row ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { column ->
              val service = row * 2 + column
              Surface(
                modifier = Modifier.weight(1f).testTag("service-$service"),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 2.dp,
              ) {
                Column(Modifier.padding(6.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      Icons.Outlined.AccountCircle,
                      contentDescription = "Service avatar",
                      modifier = Modifier.size(20.dp),
                    )
                    Text("Region $service", style = MaterialTheme.typography.titleSmall)
                  }
                  Text(
                    buildAnnotatedString {
                      append("Requests ")
                      withStyle(SpanStyle(color = Color(0xff1565c0))) {
                        append("${1200 + service * 37}")
                      }
                    },
                    fontSize = 11.sp,
                  )
                  Row(
                    Modifier.fillMaxWidth().height(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                  ) {
                    repeat(12) { bar ->
                      Box(
                        Modifier.weight(1f)
                          .height((3 + (bar * 7 + service) % 10).dp)
                          .background(Brush.verticalGradient(listOf(Color.Cyan, Color.Blue)))
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
