package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeSemanticsSnapshotTest {
  private fun payload(density: Float?, label: String = "First") =
    ComposeSemanticsPayload(
      root = ComposeSemanticsNode(nodeId = "1", boundsInRoot = "0,0,100,100", label = label),
      density = density,
    )

  @Test
  fun `one capture shares matching density but preserves unknown and changed densities`() {
    val requested = mutableListOf<Float?>()
    val snapshot = ComposeSemanticsSnapshot { density ->
      requested.add(density)
      payload(density)
    }
    assertEquals(emptyList<Float?>(), requested)
    val unknown = snapshot.payload(null)
    val one = snapshot.payload(1f)
    val two = snapshot.payload(2f)
    assertSame(unknown, snapshot.payload(null))
    assertSame(one, snapshot.payload(1f))
    assertSame(two, snapshot.payload(2f))
    assertNull(unknown.density)
    assertEquals(1f, one.density)
    assertEquals(2f, two.density)
    assertEquals(listOf(null, 1f, 2f), requested)
    val nextCapture = ComposeSemanticsSnapshot { payload(it, "Second") }
    assertNotSame(one, nextCapture.payload(1f))
    assertEquals("Second", nextCapture.payload(1f).root.label)
  }

  @Test
  fun `failed extraction is retried by the next consumer`() {
    var calls = 0
    val snapshot = ComposeSemanticsSnapshot {
      if (++calls == 1) error("failed extraction")
      payload(it)
    }
    assertThrows(IllegalStateException::class.java) { snapshot.payload(1f) }
    assertEquals(1f, snapshot.payload(1f).density)
    assertEquals(2, calls)
  }
}
