package com.example.consumerclock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.timeTextCurvedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stand-in for a consumer's own code that reads the wall clock (compose-ai-tools#5945). Lives
 * outside `ee.schimke.composeai.*` on purpose: it is "the consumer", and only its package is named
 * in `composeai.render.clockPackages` by the tests that use it.
 */
object ConsumerClockSource {
  fun now(): Long = System.currentTimeMillis()

  fun nanoNow(): Long = System.nanoTime()

  /** Millisecond precision, so two reads a second apart differ unless the clock is pinned. */
  fun format(millis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))
}

/** A consumer time-display component: reads the clock itself and paints it. */
@Composable
fun ConsumerTimeLabel(onPainted: (String) -> Unit = {}) {
  val text = ConsumerClockSource.format(System.currentTimeMillis())
  onPainted(text)
  Text(text, color = Color.White, modifier = Modifier.background(Color.Black))
}

/**
 * A consumer screen that wraps Wear Material3's `TimeText` *and* reads the clock itself, so the
 * Wear shadow and the consumer-clock path run in one composition.
 */
@Composable
fun ConsumerWearM3Screen(onTimeText: (String) -> Unit, onLabel: (String) -> Unit) {
  androidx.wear.compose.material3.MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
      androidx.wear.compose.material3.TimeText { time ->
        onTimeText(time)
        timeTextCurvedText(time)
      }
      ConsumerTimeLabel(onLabel)
    }
  }
}

/** The Wear Material (M2) equivalent of [ConsumerWearM3Screen]. */
@Composable
fun ConsumerWearM2Screen(onTimeText: (String) -> Unit, onLabel: (String) -> Unit) {
  androidx.wear.compose.material.MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
      val source =
        androidx.wear.compose.material.TimeTextDefaults.timeSource(
          androidx.wear.compose.material.TimeTextDefaults.timeFormat()
        )
      onTimeText(source.currentTime)
      androidx.wear.compose.material.TimeText(timeSource = source)
      ConsumerTimeLabel(onLabel)
    }
  }
}
