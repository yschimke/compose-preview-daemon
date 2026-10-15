package com.example.daemonclock

import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stand-in for a user module's own class that reads the wall clock — outside
 * `ee.schimke.composeai.*` because it plays the consumer. [ConsumerClockRewriterTest] defines it
 * through [ee.schimke.composeai.daemon.UserClassLoaderHolder]'s child loader, as the daemon does
 * with `composeai.daemon.userClassDirs` set.
 */
object ClockReader {
  @JvmStatic fun now(): Long = System.currentTimeMillis()

  @JvmStatic fun nanoNow(): Long = System.nanoTime()
}

/**
 * A consumer preview that paints its own wall-clock read to the millisecond, so two renders a
 * second apart differ unless the clock is pinned. Rendered end to end by
 * `ConsumerClockDaemonRenderTest`.
 */
@Composable
fun ConsumerClockLabelPreview() {
  val text = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(System.currentTimeMillis()))
  Text(text, color = Color.White, modifier = Modifier.background(Color.Black))
}
