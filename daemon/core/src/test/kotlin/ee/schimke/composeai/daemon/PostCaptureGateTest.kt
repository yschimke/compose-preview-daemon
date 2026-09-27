package ee.schimke.composeai.daemon

import org.junit.Assert.assertFalse
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
}
