package ee.schimke.composeai.renderer

import com.github.takahirom.roborazzi.AwtImageWriter
import com.github.takahirom.roborazzi.CaptureResult
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.JvmImageIoFormat
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.awt.image.BufferedImage
import java.io.File

/**
 * Settles default-format recording captures before encoding the final PNG.
 *
 * [capture] must honor its supplied options and must not read intermediate output files. Other
 * modes, writers, reporters and annotated captures retain file-based settling. Reports are
 * delivered only after the final PNG has been encoded and validated.
 */
@OptIn(ExperimentalRoborazziApi::class)
public fun captureRoborazziVisuallySettledFrame(
  file: File,
  role: String,
  options: RoborazziOptions,
  advanceFrame: () -> Unit,
  onFinalDecodedFrame: (BufferedImage) -> Unit,
  capture: (File, RoborazziOptions) -> Unit,
): VisualSettleOutcome {
  if (!canDeferStillEncoding(file, options)) {
    return captureVisuallySettledFrame(file, role, advanceFrame, onFinalDecodedFrame) {
      capture(it, options)
    }
  }
  val originalFormat = options.recordOptions.imageIoFormat as JvmImageIoFormat
  val reports = mutableListOf<Pair<CaptureResult, RoborazziTaskType>>()
  var frame: BufferedImage? = null
  var context: Map<String, Any> = emptyMap()
  val deferredOptions =
    options.copy(
      recordOptions =
        options.recordOptions.copy(
          imageIoFormat =
            originalFormat.copy(
              awtImageWriter =
                AwtImageWriter { target, metadata, image ->
                  check(target.absoluteFile.normalize() == file.absoluteFile.normalize()) {
                    "Unexpected settling output: $target"
                  }
                  check(frame == null) { "Multiple images in one settling sample" }
                  frame = image
                  context = metadata.toMap()
                }
            )
        ),
      reportOptions =
        options.reportOptions.copy(
          captureResultReporter =
            object : RoborazziOptions.CaptureResultReporter {
              override fun report(
                captureResult: CaptureResult,
                roborazziTaskType: RoborazziTaskType,
              ) {
                reports += captureResult to roborazziTaskType
              }
            }
        ),
    )
  try {
    return sampleVisuallySettledFrames(
      advanceFrame,
      onFinalDecodedFrame = { image, pixels ->
        val decoded =
          captureDecodableFrame(file, role) {
            originalFormat.awtImageWriter.write(it, context, image)
          }
        check(
          image.width == decoded.width &&
            image.height == decoded.height &&
            pixels.contentEquals(
              decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width)
            )
        ) {
          "$role final PNG changed pixels during encoding"
        }
        onFinalDecodedFrame(decoded)
        for ((result, taskType) in reports) options.reportOptions.captureResultReporter.report(
          result,
          taskType,
        )
      },
    ) {
      frame = null
      capture(file, deferredOptions)
      checkNotNull(frame) { "$role capture did not deliver an image" }
    }
  } finally {
    frame = null
    context = emptyMap()
    reports.clear()
  }
}

@OptIn(ExperimentalRoborazziApi::class)
internal fun canDeferStillEncoding(file: File, options: RoborazziOptions): Boolean {
  val format = options.recordOptions.imageIoFormat as? JvmImageIoFormat ?: return false
  val defaults = JvmImageIoFormat()
  return file.extension.equals("png", ignoreCase = true) &&
    options.taskType == RoborazziTaskType.Record &&
    options.uiTreeDumpOptions == null &&
    options.recordOptions.pixelBitConfig == RoborazziOptions.PixelBitConfig.Argb8888 &&
    format.awtImageWriter === defaults.awtImageWriter &&
    format.awtImageLoader === defaults.awtImageLoader &&
    options.reportOptions.captureResultReporter is
      RoborazziOptions.CaptureResultReporter.DefaultCaptureResultReporter
}
