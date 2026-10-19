package ee.schimke.composeai.daemon

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule that decides whether a manifest's captures name the daemon's still (#6114). */
class DeclaredRenderOutputTest {

  private fun captures(vararg json: String): List<JsonObject> = json.map {
    Json.parseToJsonElement(it).jsonObject
  }

  @Test
  fun `a single plain capture names the still`() {
    assertEquals(
      "renders/Foo.png",
      DeclaredRenderOutput.stillOutput(captures("""{"renderOutput": "renders/Foo.png"}""")),
    )
  }

  @Test
  fun `settle, a static scroll and bookkeeping fields still describe the still`() {
    assertEquals(
      "renders/Foo_SCROLL_end.png",
      DeclaredRenderOutput.stillOutput(
        captures(
          """{"renderOutput": "renders/Foo_SCROLL_end.png", "optional": false, "cost": 1.0,
             "settle": {"afterMs": 0, "maxMs": 1000}, "scroll": {"mode": "END"}}"""
        )
      ),
    )
  }

  @Test
  fun `null-valued fields do not count as behaviour`() {
    assertEquals(
      "renders/Foo.png",
      DeclaredRenderOutput.stillOutput(
        captures("""{"renderOutput": "renders/Foo.png", "advanceTimeMillis": null}""")
      ),
    )
  }

  @Test
  fun `a still beside a GIF capture is still unambiguous`() {
    assertEquals(
      "lottie/Foo.png",
      DeclaredRenderOutput.stillOutput(
        captures(
          """{"renderOutput": "lottie/Foo.png"}""",
          """{"renderOutput": "lottie/Foo_animated.png", "animation": {"durationMs": 500}}""",
        )
      ),
    )
  }

  @Test
  fun `two candidate stills have no honest answer`() {
    assertNull(
      DeclaredRenderOutput.stillOutput(
        captures(
          """{"renderOutput": "renders/Foo_SCROLL_top.png", "scroll": {"mode": "TOP"}}""",
          """{"renderOutput": "renders/Foo_SCROLL_end.png", "scroll": {"mode": "END"}}""",
        )
      )
    )
  }

  @Test
  fun `time steps, interactions and unknown fields are not the still`() {
    for (capture in
      listOf(
        """{"renderOutput": "renders/Foo_TIME_500ms.png", "advanceTimeMillis": 500}""",
        """{"renderOutput": "renders/Foo.png", "interaction": {"kind": "HOVER"}}""",
        """{"renderOutput": "renders/Foo.png", "aFieldFromTheFuture": {}}""",
        """{"renderOutput": "renders/Foo.png", "scroll": {"mode": "LONG"}}""",
      )) {
      assertNull(capture, DeclaredRenderOutput.stillOutput(captures(capture)))
    }
  }

  @Test
  fun `a non-PNG or empty output is never written as a PNG`() {
    assertNull(
      DeclaredRenderOutput.stillOutput(captures("""{"renderOutput": "renders/Foo.gif"}"""))
    )
    assertNull(DeclaredRenderOutput.stillOutput(captures("""{"renderOutput": ""}""")))
    assertNull(DeclaredRenderOutput.stillOutput(captures("""{}""")))
    assertNull(DeclaredRenderOutput.stillOutput(emptyList()))
  }

  @Test
  fun `a row is suffixed before the extension of the leaf`() {
    assertEquals("renders/Foo_Dark.png", DeclaredRenderOutput.forRow("renders/Foo.png", "Dark"))
    assertEquals(
      "renders/a.b/Foo_PARAM_2.png",
      DeclaredRenderOutput.forRow("renders/a.b/Foo.png", "PARAM_2"),
    )
    assertEquals("renders/Foo.png", DeclaredRenderOutput.forRow("renders/Foo.png", null))
  }

  @Test
  fun `a relative output resolves against the manifest directory, an absolute one is kept`() {
    val manifestDir = File("/work/bazel-out/previews")
    assertEquals(
      File("/work/bazel-out/previews/renders/Foo.png"),
      DeclaredRenderOutput.resolve(manifestDir, "renders/Foo.png"),
    )
    assertEquals(
      File("/work/bazel-out/renders/Foo.png"),
      DeclaredRenderOutput.resolve(manifestDir, "../renders/Foo.png"),
    )
    assertEquals(
      File("/elsewhere/Foo.png"),
      DeclaredRenderOutput.resolve(manifestDir, "/elsewhere/Foo.png"),
    )
  }

  @Test
  fun `a Spec target round-trips its output path and still decodes without one`() {
    val spec = RenderSpec(className = "a.B", functionName = "c")
    val withPath = RenderTarget.Spec(spec, outputPath = "/out/Foo.png")
    assertEquals(withPath, RenderTarget.decode(RenderTarget.encode(withPath)))
    val legacy = RenderTarget.Spec(spec)
    assertNull((RenderTarget.decode(RenderTarget.encode(legacy)) as RenderTarget.Spec).outputPath)
  }
}
