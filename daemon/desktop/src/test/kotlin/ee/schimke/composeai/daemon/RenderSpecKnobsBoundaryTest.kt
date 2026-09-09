package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A preview's **parameter knobs** must survive the boundary hop into a sandbox — the Robolectric
 * classloader crossing and the sandbox worker process — because that is where composition happens
 * and a knob the sandbox cannot see renders its author default with no error.
 *
 * They used to travel as a bespoke `knobs=<name>:<index>:<TYPE>[:<default>],…` token appended to
 * the render payload, with its own hand-written parser and forgiveness rules, precisely because the
 * spec could not cross as an object. [RenderSpec] is `@Serializable` now, so the knobs ride it like
 * every other field and the token is gone.
 */
class RenderSpecKnobsBoundaryTest {

  private val knobs =
    listOf(
      PreviewKnobDto("title", 0, "STRING", default = "Hello"),
      PreviewKnobDto("enabled", 1, "BOOLEAN"),
      PreviewKnobDto("count", 2, "INT"),
    )

  @Test
  fun `knobs survive the boundary round-trip in order`() {
    val spec = RenderSpec(className = "com.example.FooKt", functionName = "Foo", knobs = knobs)
    assertEquals(knobs, RenderSpec.decode(RenderSpec.encode(spec)).knobs)
  }

  /**
   * A knob kind this daemon has never heard of is carried through verbatim for the binder to drop —
   * the parameter takes its compiled default while every other knob still seeds. Failing the render
   * because a kind is unfamiliar would turn a forward-compatible wire into a hard version pin.
   */
  @Test
  fun `an unknown kind survives the hop and is dropped later by the binder`() {
    val spec =
      RenderSpec(
        className = "com.example.FooKt",
        functionName = "Foo",
        knobs = listOf(PreviewKnobDto("accent", 0, "COLOR")),
      )
    assertEquals("COLOR", RenderSpec.decode(RenderSpec.encode(spec)).knobs.single().type)
  }

  /**
   * A default is author-written text and may legitimately contain `:`, `,`, `;` or `=` — every one
   * of which was a delimiter in the token this replaced, which is why the token had to base64 the
   * field. JSON needs no such precaution.
   */
  @Test
  fun `a default full of former delimiters needs no escaping`() {
    val awkward = PreviewKnobDto("label", 0, "STRING", default = "a:b,c;d=e")
    val spec =
      RenderSpec(className = "com.example.FooKt", functionName = "Foo", knobs = listOf(awkward))
    assertEquals(awkward, RenderSpec.decode(RenderSpec.encode(spec)).knobs.single())
  }

  @Test
  fun `a spec with no knobs decodes to none`() {
    val spec = RenderSpec(className = "com.example.FooKt", functionName = "Foo")
    assertEquals(emptyList<PreviewKnobDto>(), RenderSpec.decode(RenderSpec.encode(spec)).knobs)
  }
}
