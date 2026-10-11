package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCaptureGateTest {

  private val figma = "compose/figma-svg"

  @Test
  fun onDemandKindRunsOnlyWhenRequested() {
    assertFalse(PostCaptureGate.shouldRun(figma, emptySet()))
    assertFalse(PostCaptureGate.shouldRun(figma, setOf("a11y/hierarchy")))
    assertTrue(PostCaptureGate.shouldRun(figma, setOf(figma)))
  }

  @Test
  fun unknownRequestSetRunsEverything() {
    // A caller that predates the gate (null) keeps the full artefact set.
    assertTrue(PostCaptureGate.shouldRun(figma, null))
  }

  @Test
  fun alwaysOnKindsIgnoreTheRequestSet() {
    assertTrue(PostCaptureGate.shouldRun("fonts/used", emptySet()))
    assertTrue(PostCaptureGate.shouldRun("compose/semantics", emptySet()))
  }

  @Test
  fun skippedMarkerIsReadFromMetrics() {
    val skipped = mapOf(PostCaptureGate.skippedMetricKey(figma) to 0L)
    assertTrue(PostCaptureGate.wasSkipped(skipped, figma))
    assertFalse(PostCaptureGate.wasSkipped(mapOf(PostCaptureGate.ranMetricKey(figma) to 9L), figma))
    assertFalse(PostCaptureGate.wasSkipped(null, figma))
  }

  @Test
  fun allowlistMakesEveryProcessorOnDemand() {
    val allow = setOf("compose/semantics", "layout/inspector")
    fun run(kind: String, requested: Set<String>?) =
      PostCaptureGate.shouldRun(kind, requested, PostCaptureGate.ON_DEMAND_KINDS, allow)

    assertTrue(run("compose/semantics", emptySet()))
    assertTrue(run("layout/inspector", null))
    assertFalse(run("compose/semantics-wireframe", emptySet()))
    // A null request set no longer means "everything" once the operator named the kinds.
    assertFalse(run(figma, null))
    // A subscription / fetch still reaches its processor.
    assertTrue(run(figma, setOf(figma)))
  }

  @Test
  fun aSidecarKindRunsTheProcessorThatWritesIt() {
    // The wireframe processor also writes compose/spatial-semantics.
    assertTrue(
      PostCaptureGate.shouldRun(
        "compose/semantics-wireframe",
        requested = setOf("compose/spatial-semantics"),
        onDemandKinds = emptySet(),
        allowlist = setOf("compose/semantics"),
      )
    )
  }

  @Test
  fun noAllowlistKeepsEachEngineDefault() {
    // Desktop's default (no on-demand kinds) runs everything, requested or not.
    assertTrue(PostCaptureGate.shouldRun(figma, emptySet(), emptySet(), null))
    // Android's default matches the two-argument overload.
    assertFalse(PostCaptureGate.shouldRun(figma, emptySet(), PostCaptureGate.ON_DEMAND_KINDS, null))
  }

  @Test
  fun allowlistIsReadFromTheSystemProperty() {
    val name = DaemonProperties.Names.POST_CAPTURE_KINDS
    val previous = System.getProperty(name)
    try {
      System.clearProperty(name)
      assertNull(PostCaptureGate.allowlistFromSystemProperties())
      System.setProperty(name, "")
      assertNull(PostCaptureGate.allowlistFromSystemProperties())
      System.setProperty(name, "compose/semantics, layout/inspector")
      assertEquals(
        setOf("compose/semantics", "layout/inspector"),
        PostCaptureGate.allowlistFromSystemProperties(),
      )
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
  }
}
