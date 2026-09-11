package ee.schimke.composeai.renderer

import com.github.takahirom.roborazzi.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalRoborazziApi::class)
class InMemoryStillCaptureTest {
  @get:Rule val temporary = TemporaryFolder()
  private var oldResultDir: String? = null
  private lateinit var reportDirectory: java.io.File

  @Before
  fun isolateReports() {
    oldResultDir = System.getProperty("roborazzi.result.dir")
    reportDirectory = temporary.newFolder("reports")
    System.setProperty("roborazzi.result.dir", reportDirectory.path)
  }

  @After
  fun restore() {
    if (oldResultDir == null) System.clearProperty("roborazzi.result.dir")
    else System.setProperty("roborazzi.result.dir", oldResultDir!!)
  }

  @Test
  fun `released canvas images settle and encode only the final frame`() {
    for ((colors, expected) in
      listOf(
        listOf(1) to VisualSettleOutcome.NEVER_CHANGED,
        listOf(1, 2, 2, 2) to VisualSettleOutcome.SETTLED,
        listOf(1, 2) to VisualSettleOutcome.STILL_CHANGING,
      )) {
      val output = temporary.newFolder().resolve("frame.png")
      val options =
        RoborazziOptions(
          taskType = RoborazziTaskType.Record,
          captureType = RoborazziOptions.CaptureType.Screenshot(),
          uiTreeDumpOptions = null,
        )
      assertTrue(canDeferStillEncoding(output, options))
      val priorReports = reportDirectory.listFiles()!!.size
      var captures = 0
      var advances = 0
      var callbacks = 0
      var finalColor = 0
      val outcome =
        captureRoborazziVisuallySettledFrame(
          output,
          "canvas",
          options,
          advanceFrame = { advances++ },
          onFinalDecodedFrame = {
            callbacks++
            assertEquals(finalColor, it.getRGB(4, 2))
          },
        ) { file, current ->
          assertFalse("Intermediate PNG must not be written", file.exists())
          assertEquals(priorReports, reportDirectory.listFiles()!!.size)
          val image = BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB)
          finalColor = 0xff000000.toInt() or colors[captures++ % colors.size]
          image.setRGB(4, 2, finalColor)
          val canvas = AwtRoboCanvas(8, 7, false, BufferedImage.TYPE_INT_ARGB)
          canvas.drawImage(image, AwtRoboCanvas.CompositeMode.Src)
          try {
            canvas.save(
              file.path,
              1.0,
              mapOf("probe" to "metadata-survives"),
              current.recordOptions.imageIoFormat,
            )
          } finally {
            canvas.release()
          }
          current.reportOptions.captureResultReporter.report(
            CaptureResult.Recorded(file.absolutePath, System.nanoTime(), emptyMap()),
            RoborazziTaskType.Record,
          )
        }
      assertEquals(expected, outcome)
      assertEquals(
        if (expected == VisualSettleOutcome.SETTLED) 4 else VISUAL_SETTLE_MAX_SAMPLES,
        captures,
      )
      assertEquals(captures - 1, advances)
      assertEquals(1, callbacks)
      assertEquals(priorReports + captures, reportDirectory.listFiles()!!.size)
      val result = ImageIO.read(output)
      assertEquals(5, result.width)
      assertEquals(3, result.height)
      assertEquals(finalColor, result.getRGB(4, 2))
      ImageIO.createImageInputStream(output).use { input ->
        val reader = ImageIO.getImageReaders(input).next()
        try {
          reader.input = input
          val tree = reader.getImageMetadata(0).getAsTree("javax_imageio_1.0")
          val entries =
            (tree as javax.imageio.metadata.IIOMetadataNode).getElementsByTagName("TextEntry")
          assertTrue(
            (0 until entries.length).any {
              entries.item(it).attributes.getNamedItem("value")?.nodeValue == "metadata-survives"
            }
          )
        } finally {
          reader.dispose()
        }
      }
    }
  }

  @Test
  fun `missing image and capture failure never deliver a final callback`() {
    var callbacks = 0
    val options =
      RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        captureType = RoborazziOptions.CaptureType.Screenshot(),
        uiTreeDumpOptions = null,
      )
    assertThrows(IllegalStateException::class.java) {
      captureRoborazziVisuallySettledFrame(
        temporary.newFolder().resolve("missing.png"),
        "missing",
        options,
        {},
        { callbacks++ },
      ) { _, _ ->
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      captureRoborazziVisuallySettledFrame(
        temporary.newFolder().resolve("failure.png"),
        "failure",
        options,
        {},
        { callbacks++ },
      ) { _, _ ->
        throw IllegalArgumentException("capture failed")
      }
    }
    assertEquals(0, callbacks)
  }

  @Test
  fun `custom encoders keep decoder validation on the fallback path`() {
    var callbacks = 0
    val options =
      RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        captureType = RoborazziOptions.CaptureType.Screenshot(),
        uiTreeDumpOptions = null,
        recordOptions =
          RoborazziOptions.RecordOptions(
            imageIoFormat =
              JvmImageIoFormat(
                awtImageWriter = AwtImageWriter { file, _, _ -> file.writeText("not a png") }
              )
          ),
      )
    val output = temporary.newFolder().resolve("bad.png")
    assertFalse(canDeferStillEncoding(output, options))
    assertThrows(IllegalStateException::class.java) {
      captureRoborazziVisuallySettledFrame(output, "bad", options, {}, { callbacks++ }) {
        file,
        current ->
        (current.recordOptions.imageIoFormat as JvmImageIoFormat)
          .awtImageWriter
          .write(file, emptyMap(), BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
      }
    }
    assertEquals(0, callbacks)
  }

  @Test
  fun `only plain recording and default png configuration qualify`() {
    val output = temporary.newFolder().resolve("guard.png")
    val options =
      RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        captureType = RoborazziOptions.CaptureType.Screenshot(),
        uiTreeDumpOptions = null,
      )
    for (task in RoborazziTaskType.entries) {
      assertEquals(
        task == RoborazziTaskType.Record,
        canDeferStillEncoding(output, options.copy(taskType = task)),
      )
    }
    assertFalse(canDeferStillEncoding(output.resolveSibling("guard.webp"), options))
    assertFalse(
      canDeferStillEncoding(
        output,
        options.copy(
          recordOptions =
            options.recordOptions.copy(pixelBitConfig = RoborazziOptions.PixelBitConfig.Rgb565)
        ),
      )
    )
  }

  @Test
  fun `failed final encoding publishes neither callback nor queued success reports`() {
    val file = temporary.newFolder("directory.png")
    file.resolve("keep-directory-nonempty").writeText("block replacement by ImageIO")
    val options =
      RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        captureType = RoborazziOptions.CaptureType.Screenshot(),
        uiTreeDumpOptions = null,
      )
    var callbacks = 0
    var captures = 0
    assertThrows(Exception::class.java) {
      captureRoborazziVisuallySettledFrame(file, "encode failure", options, {}, { callbacks++ }) {
        target,
        current ->
        captures++
        (current.recordOptions.imageIoFormat as JvmImageIoFormat)
          .awtImageWriter
          .write(
            target,
            emptyMap(),
            BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
          )
        current.reportOptions.captureResultReporter.report(
          CaptureResult.Recorded(target.absolutePath, System.nanoTime(), emptyMap()),
          RoborazziTaskType.Record,
        )
      }
    }
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, captures)
    assertEquals(0, callbacks)
    assertEquals(0, reportDirectory.listFiles()!!.size)
  }

  @Test
  fun `custom reporter retains immediate file availability through fallback`() {
    val output = temporary.newFolder().resolve("reported.png")
    var reports = 0
    val options =
      RoborazziOptions(
        taskType = RoborazziTaskType.Record,
        captureType = RoborazziOptions.CaptureType.Screenshot(),
        uiTreeDumpOptions = null,
        reportOptions =
          RoborazziOptions.ReportOptions(
            object : RoborazziOptions.CaptureResultReporter {
              override fun report(
                captureResult: CaptureResult,
                roborazziTaskType: RoborazziTaskType,
              ) {
                assertTrue(output.isFile)
                reports++
              }
            }
          ),
      )
    assertFalse(canDeferStillEncoding(output, options))
    captureRoborazziVisuallySettledFrame(output, "reported", options, {}, {}) { target, current ->
      (current.recordOptions.imageIoFormat as JvmImageIoFormat)
        .awtImageWriter
        .write(
          target,
          emptyMap(),
          BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
        )
      current.reportOptions.captureResultReporter.report(
        CaptureResult.Recorded(target.absolutePath, System.nanoTime(), emptyMap()),
        RoborazziTaskType.Record,
      )
    }
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, reports)
  }
}
