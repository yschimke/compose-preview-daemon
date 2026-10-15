package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ConsumerClock
import ee.schimke.composeai.renderer.PreviewClock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end consumer clock pinning on the daemon's sandbox lane (compose-ai-tools#5945): a real
 * [RobolectricHost] render of a consumer preview that paints `System.currentTimeMillis()` to the
 * millisecond produces byte-identical PNGs a second apart once `composeai.render.clockPackages`
 * names the consumer's package — the [SandboxHoldingRunner] instrumentation plus
 * [ee.schimke.composeai.renderer.ShadowConsumerClock] at work — and differing ones with
 * `fixedTime=off`, which is the control that proves the capture can see the clock at all.
 *
 * The property is set before the host boots because it shapes the sandbox's instrumentation;
 * `forkEvery = 1` keeps that sandbox to this class's JVM. The child-loader lane, which a render
 * with `composeai.daemon.userClassDirs` takes, is [ConsumerClockRewriterTest]'s.
 */
class ConsumerClockDaemonRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun restoreGlobals() {
    System.clearProperty(ConsumerClock.PROPERTY)
    System.clearProperty(PreviewClock.PROPERTY)
  }

  @Test
  fun `a consumer clock preview renders identically at two host times, and drifts when off`() {
    System.setProperty(ConsumerClock.PROPERTY, "com.example.daemonclock.")
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      val first = render(host, "pinned-1")
      Thread.sleep(1_100)
      val second = render(host, "pinned-2")
      assertArrayEquals(first, second)

      System.setProperty(PreviewClock.PROPERTY, "off")
      val third = render(host, "off-1")
      Thread.sleep(1_100)
      val fourth = render(host, "off-2")
      assertFalse("host-clock renders a second apart should differ", third.contentEquals(fourth))
    } finally {
      host.shutdown()
    }
  }

  private fun render(host: RobolectricHost, name: String): ByteArray {
    val outputDir = tempFolder.newFolder("renders-$name")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    host.submit(
      RenderRequest.Render(
        target =
          RenderTarget.Spec(
            RenderSpec(
              previewId = name,
              className = "com.example.daemonclock.ClockReaderKt",
              functionName = "ConsumerClockLabelPreview",
              widthPx = 160,
              heightPx = 40,
              density = 1.0f,
              showBackground = false,
              outputBaseName = name,
            )
          )
      ),
      timeoutMs = 120_000,
    )
    val png =
      outputDir.walkTopDown().firstOrNull { it.isFile && it.name == "$name.png" }
        ?: error("no $name.png under $outputDir: ${outputDir.walkTopDown().toList()}")
    return png.readBytes().also { assertTrue("empty render $png", it.isNotEmpty()) }
  }
}
