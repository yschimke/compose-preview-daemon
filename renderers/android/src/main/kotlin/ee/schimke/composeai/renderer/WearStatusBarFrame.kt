package ee.schimke.composeai.renderer

import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Typeface
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Documented Wear OS Global Status Bar indicators, ordered from highest to lowest display priority.
 *
 * The Wear OS status bar displays the time curved along the 12 o'clock bezel arc alongside at most
 * one status/privacy icon.
 */
enum class WearStatusBarIndicator(val tint: Color) {
  MICROPHONE(Color(0xFF81C784)),
  LOCATION(Color(0xFF81C784)),
  AIRPLANE(Color(0xFFFFFFFF)),
  DND(Color(0xFFFFFFFF)),
  BEDTIME(Color(0xFF90CAF9)),
  THEATER(Color(0xFFFFFFFF)),
  BATTERY_SAVER(Color(0xFFFFD54F)),
  CHARGING(Color(0xFF81C784)),
  LOW_BATTERY(Color(0xFFEF5350)),
  OFFLINE(Color(0xFFBDBDBD));

  companion object {
    fun parse(raw: String?): WearStatusBarIndicator? {
      val normalized = raw?.trim()?.lowercase()?.replace("-", "_")?.replace(" ", "_") ?: return null
      if (normalized.isEmpty()) return null
      return when (normalized) {
        "mic",
        "microphone" -> MICROPHONE
        "loc",
        "location" -> LOCATION
        "airplane",
        "airplane_mode" -> AIRPLANE
        "dnd",
        "do_not_disturb" -> DND
        "bedtime",
        "bedtime_mode" -> BEDTIME
        "theater",
        "theatre",
        "theater_mode" -> THEATER
        "battery_saver",
        "batterysaver" -> BATTERY_SAVER
        "charging" -> CHARGING
        "low_battery",
        "lowbattery" -> LOW_BATTERY
        "offline",
        "disconnected" -> OFFLINE
        else -> null
      }
    }
  }
}

/**
 * Synthetic Wear OS Global Status Bar (GSB) overlay for round Wear previews and daemon sessions.
 *
 * Sibling of [SystemBarsFrame] for Wear OS 8 / Wear OS 7.1 Canary: draws the curved 12 o'clock
 * clock (and optional single priority mode/privacy indicator) following the circular bezel arc over
 * a subtle dark curved backplate, matching Wear OS 8's system status bar.
 *
 * Visibility stays coupled to the host window's `WindowInsetsController` status bar state via
 * [WearStatusBarSupport.isStatusBarVisible]: whenever `Modifier.suppressStatusBar()`,
 * `AlertDialog`, `TimePicker`, `DatePicker`, `Stepper`, custom `TimeText`, or scroll auto-hide
 * hides `WindowInsets.Type.statusBars()`, the status bar disappears automatically and restores when
 * shown again. Setting [ambient] to `true` also hides the status bar.
 */
@Composable
fun WearStatusBarFrame(
  clockText: String = WearStatusBarSupport.DEFAULT_CLOCK_TEXT,
  indicator: WearStatusBarIndicator? = null,
  ambient: Boolean = false,
  content: @Composable () -> Unit,
) {
  val view = LocalView.current
  var systemBarVisible by
    remember(view) { mutableStateOf(WearStatusBarSupport.isStatusBarVisible(view)) }

  DisposableEffect(view) {
    val listener = ViewTreeObserver.OnPreDrawListener {
      val visible = WearStatusBarSupport.isStatusBarVisible(view)
      if (visible != systemBarVisible) {
        systemBarVisible = visible
      }
      true
    }
    val observer = view.viewTreeObserver
    observer.addOnPreDrawListener(listener)
    onDispose {
      if (observer.isAlive) {
        observer.removeOnPreDrawListener(listener)
      }
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    content()
    if (!ambient && systemBarVisible) {
      WearCurvedStatusBarOverlay(
        clockText = clockText,
        indicator = indicator,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun WearCurvedStatusBarOverlay(
  clockText: String,
  indicator: WearStatusBarIndicator?,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val diameter = minOf(size.width, size.height)
    if (diameter <= 0f) return@Canvas
    val cx = size.width / 2f
    val cy = size.height / 2f
    val screenRadius = diameter / 2f

    // Center-line radius for the curved status bar sits inside the top bezel (~14dp inset),
    // matching Wear OS 8's curved 12 o'clock TimeText / status bar track.
    val trackRadius = (screenRadius - TRACK_INSET_DP.dp.toPx()).coerceAtLeast(16f)
    val textSizePx = CLOCK_TEXT_SIZE_SP.sp.toPx()
    val iconSizePx = ICON_SIZE_DP.dp.toPx()
    val gapPx = ICON_GAP_DP.dp.toPx()
    val padPx = BACKPLATE_PAD_DP.dp.toPx()

    val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = textSizePx
        typeface = resolveWearStatusBarTypeface()
        fontVariationSettings = "'wght' 600, 'wdth' 100"
        letterSpacing = CLOCK_LETTER_SPACING_EM
        textAlign = Paint.Align.CENTER
      }
    val textWidth = textPaint.measureText(clockText)
    val fm = textPaint.fontMetrics
    val baselineOffset = -(fm.ascent + fm.descent) / 2f

    val totalArcPx = if (indicator != null) iconSizePx + gapPx + textWidth else textWidth
    val totalSweepDeg = ((totalArcPx / trackRadius) * (180.0 / PI)).toFloat()
    val backplatePadDeg = ((padPx / trackRadius) * (180.0 / PI)).toFloat()
    val backplateSweep = (totalSweepDeg + backplatePadDeg * 2f).coerceIn(12f, 120f)
    val backplateStart = -90f - backplateSweep / 2f

    // Subtle dark curved backplate so the white curved text stays legible over scrolled content,
    // while blending naturally into black Wear backgrounds.
    val arcRect = RectF(cx - trackRadius, cy - trackRadius, cx + trackRadius, cy + trackRadius)
    drawIntoCanvas { canvas ->
      val backplatePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          style = Paint.Style.STROKE
          strokeWidth = BACKPLATE_STROKE_DP.dp.toPx()
          strokeCap = Paint.Cap.ROUND
          color = 0xB8000000.toInt()
        }
      canvas.nativeCanvas.drawArc(arcRect, backplateStart, backplateSweep, false, backplatePaint)
    }

    // Center the combined [icon + gap + text] group around -90° (12 o'clock).
    val groupStartDeg = -90f - totalSweepDeg / 2f
    val textCenterDeg =
      if (indicator != null) {
        val iconAndGapDeg = (((iconSizePx + gapPx) / trackRadius) * (180.0 / PI)).toFloat()
        val textHalfDeg = (((textWidth / 2f) / trackRadius) * (180.0 / PI)).toFloat()
        groupStartDeg + iconAndGapDeg + textHalfDeg
      } else {
        -90f
      }

    if (indicator != null) {
      val iconHalfDeg = (((iconSizePx / 2f) / trackRadius) * (180.0 / PI)).toFloat()
      val iconCenterDeg = groupStartDeg + iconHalfDeg
      val rad = Math.toRadians(iconCenterDeg.toDouble())
      val iconX = cx + (trackRadius * cos(rad)).toFloat()
      val iconY = cy + (trackRadius * sin(rad)).toFloat()
      withTransform(
        {
          translate(left = iconX - iconSizePx / 2f, top = iconY - iconSizePx / 2f)
          rotate(degrees = iconCenterDeg + 90f, pivot = Offset(iconSizePx / 2f, iconSizePx / 2f))
        },
      ) {
        drawIndicatorGlyph(indicator, iconSizePx)
      }
    }

    // Draw the time curved along the circular arc (clockwise across 12 o'clock).
    val textArcSweep = ((textWidth / trackRadius) * (180.0 / PI)).toFloat().coerceAtLeast(8f) + 20f
    val textArcStart = textCenterDeg - textArcSweep / 2f
    val textPath = AndroidPath().apply { addArc(arcRect, textArcStart, textArcSweep) }
    drawIntoCanvas { canvas ->
      canvas.nativeCanvas.drawTextOnPath(clockText, textPath, 0f, baselineOffset, textPaint)
    }
  }
}

private fun DrawScope.drawIndicatorGlyph(indicator: WearStatusBarIndicator, iconSizePx: Float) {
  val color = indicator.tint
  val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
  val cx = iconSizePx / 2f
  val cy = iconSizePx / 2f
  val r = iconSizePx * 0.42f

  when (indicator) {
    WearStatusBarIndicator.MICROPHONE -> {
      val capsuleW = r * 0.72f
      val capsuleH = r * 1.15f
      drawRoundRect(
        color = color,
        topLeft = Offset(cx - capsuleW / 2f, cy - r * 0.85f),
        size = Size(capsuleW, capsuleH),
        cornerRadius = CornerRadius(capsuleW / 2f, capsuleW / 2f),
      )
      drawLine(
        color = color,
        start = Offset(cx, cy + r * 0.45f),
        end = Offset(cx, cy + r * 0.85f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(cx - r * 0.45f, cy + r * 0.85f),
        end = Offset(cx + r * 0.45f, cy + r * 0.85f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
    }
    WearStatusBarIndicator.LOCATION -> {
      drawCircle(color = color, radius = r * 0.52f, center = Offset(cx, cy - r * 0.18f))
      val tip =
        Path().apply {
          moveTo(cx - r * 0.44f, cy - r * 0.02f)
          lineTo(cx, cy + r * 0.85f)
          lineTo(cx + r * 0.44f, cy - r * 0.02f)
          close()
        }
      drawPath(tip, color = color)
      drawCircle(color = Color.Black, radius = r * 0.2f, center = Offset(cx, cy - r * 0.18f))
    }
    WearStatusBarIndicator.DND -> {
      drawCircle(color = color, radius = r * 0.85f, center = Offset(cx, cy))
      drawLine(
        color = Color.Black,
        start = Offset(cx - r * 0.42f, cy),
        end = Offset(cx + r * 0.42f, cy),
        strokeWidth = stroke.width * 1.2f,
        cap = StrokeCap.Round,
      )
    }
    WearStatusBarIndicator.BEDTIME -> {
      drawCircle(color = color, radius = r * 0.78f, center = Offset(cx - r * 0.1f, cy))
      drawCircle(
        color = Color.Black,
        radius = r * 0.65f,
        center = Offset(cx + r * 0.28f, cy - r * 0.12f),
      )
    }
    WearStatusBarIndicator.AIRPLANE -> {
      drawLine(
        color = color,
        start = Offset(cx, cy - r * 0.85f),
        end = Offset(cx, cy + r * 0.85f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(cx - r * 0.8f, cy - r * 0.05f),
        end = Offset(cx + r * 0.8f, cy - r * 0.05f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(cx - r * 0.4f, cy + r * 0.65f),
        end = Offset(cx + r * 0.4f, cy + r * 0.65f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
    }
    WearStatusBarIndicator.THEATER -> {
      drawCircle(color = color, radius = r * 0.82f, center = Offset(cx, cy), style = stroke)
      drawLine(
        color = color,
        start = Offset(cx - r * 0.6f, cy - r * 0.6f),
        end = Offset(cx + r * 0.6f, cy + r * 0.6f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
    }
    WearStatusBarIndicator.BATTERY_SAVER,
    WearStatusBarIndicator.CHARGING,
    WearStatusBarIndicator.LOW_BATTERY -> {
      val bodyW = r * 1.15f
      val bodyH = r * 1.5f
      drawRoundRect(
        color = color,
        topLeft = Offset(cx - bodyW / 2f, cy - bodyH / 2f + r * 0.12f),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
        style = stroke,
      )
      drawRect(
        color = color,
        topLeft = Offset(cx - bodyW * 0.25f, cy - bodyH / 2f - r * 0.1f),
        size = Size(bodyW * 0.5f, r * 0.22f),
      )
      val fillRatio =
        when (indicator) {
          WearStatusBarIndicator.LOW_BATTERY -> 0.28f
          WearStatusBarIndicator.BATTERY_SAVER -> 0.55f
          else -> 0.78f
        }
      val fillH = (bodyH - stroke.width * 2f) * fillRatio
      drawRect(
        color = color,
        topLeft =
          Offset(
            cx - bodyW / 2f + stroke.width,
            cy + bodyH / 2f + r * 0.12f - stroke.width - fillH,
          ),
        size = Size(bodyW - stroke.width * 2f, fillH),
      )
    }
    WearStatusBarIndicator.OFFLINE -> {
      drawCircle(color = color, radius = r * 0.75f, center = Offset(cx, cy), style = stroke)
      drawLine(
        color = color,
        start = Offset(cx - r * 0.7f, cy + r * 0.7f),
        end = Offset(cx + r * 0.7f, cy - r * 0.7f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Resolves the Wear OS system status bar typeface: prefers Pixel/Wear OS `google-sans-flex` (seeded
 * into the system font map by [PixelSystemFontAliases]), then `roboto-flex` (Wear M3's AOSP
 * `TypefaceTokens.Brand`), falling back to `sans-serif-medium` when neither variable family is
 * seeded (for example in an offline sandbox with an empty font cache).
 */
private fun resolveWearStatusBarTypeface(): Typeface {
  val defaultFace = Typeface.DEFAULT
  for (family in listOf("google-sans-flex", "roboto-flex")) {
    val candidate = Typeface.create(family, Typeface.NORMAL)
    if (candidate != null && candidate != defaultFace) {
      return Typeface.create(candidate, 600, false)
    }
  }
  return Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private const val TRACK_INSET_DP = 13
private const val CLOCK_TEXT_SIZE_SP = 15
private const val CLOCK_LETTER_SPACING_EM = 0.04f
private const val ICON_SIZE_DP = 13
private const val ICON_GAP_DP = 4
private const val BACKPLATE_PAD_DP = 8
private const val BACKPLATE_STROKE_DP = 20
