package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SemanticsPoint
import ee.schimke.composeai.data.layoutinspector.SemanticsRefs
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM coverage of [MergedSemanticsTargeting] (compose-ai-tools#5945): the unmerged and merged
 * trees here are hand-built in the shapes Compose produces for `Button { Text(…) }`, so the
 * fallback rules are pinned without booting a sandbox. `AndroidInteractiveSessionTest` drives the
 * same fixes end to end against real Material components.
 */
class MergedSemanticsTargetingTest {

  @Test
  fun roleAndTextSplitAcrossButtonAndChildResolvesThroughTheMergedTree() {
    var mergedAsked = false
    val res =
      MergedSemanticsTargeting.resolve(
        unmergedButton(),
        SemanticsTarget.RoleText("Button", "Example Button"),
      ) {
        mergedAsked = true
        mergedButton()
      }
    assertTrue("an unmerged miss on a role+text target must consult the merged tree", mergedAsked)
    assertTrue("expected Resolved, got $res", res is TargetResolution.Resolved)
    val resolved = res as TargetResolution.Resolved
    assertEquals("2", resolved.node.nodeId)
    assertEquals(SemanticsPoint(80, 30), resolved.point)
    assertEquals(
      "the resolved node must carry the unmerged tree's ref for the same nodeId",
      refOf(unmergedButton(), "2"),
      resolved.node.ref,
    )
  }

  @Test
  fun anUnmergedMatchStillWinsAndTheMergedTreeIsNotConsulted() {
    // Text alone matches the unmerged `Text` child — the node that resolved before this change.
    val res =
      MergedSemanticsTargeting.resolve(
        unmergedButton(),
        SemanticsTarget.RoleText(text = "Example Button"),
      ) {
        fail("the merged tree must not be consulted when the unmerged tree already resolves")
        null
      }
    assertEquals("3", (res as TargetResolution.Resolved).node.nodeId)
  }

  @Test
  fun tagAndRefTargetsAreNeverRetriedAgainstTheMergedTree() {
    for (target in listOf(SemanticsTarget.Tag("nope"), SemanticsTarget.Ref("nope"))) {
      val res =
        MergedSemanticsTargeting.resolve(unmergedButton(), target) {
          fail("$target must not be retried against the merged tree")
          null
        }
      assertSame(TargetResolution.NotFound, res)
    }
  }

  @Test
  fun aMissInBothTreesIsStillNotFound() {
    val res =
      MergedSemanticsTargeting.resolve(
        unmergedButton(),
        SemanticsTarget.RoleText("Button", "Other"),
      ) {
        mergedButton()
      }
    assertSame(TargetResolution.NotFound, res)
  }

  @Test
  fun aMergedAmbiguityIsReportedWithUnmergedRefs() {
    val unmerged = twoButtons(merged = false)
    val res =
      MergedSemanticsTargeting.resolve(
        unmerged,
        SemanticsTarget.RoleText("Button", "Example Button"),
      ) {
        twoButtons(merged = true)
      }
    assertTrue("expected Ambiguous, got $res", res is TargetResolution.Ambiguous)
    val ambiguous = res as TargetResolution.Ambiguous
    assertEquals(listOf("2", "4"), ambiguous.candidates.map { it.nodeId })
    assertEquals(
      listOf(refOf(unmerged, "2"), refOf(unmerged, "4")),
      ambiguous.candidates.map { it.ref },
    )
  }

  @Test
  fun actionTargetWalksUpToTheNearestAncestorExposingTheAction() {
    // root(1) > button(2, OnClick) > icon(3, contentDescription)
    val parents = mapOf(3 to 2, 2 to 1)
    val exposing = setOf(2)
    assertEquals(
      2,
      MergedSemanticsTargeting.actionTarget(listOf(3), parents::get) { it in exposing },
    )
  }

  @Test
  fun actionTargetKeepsAMatchThatExposesTheActionItself() {
    val parents = mapOf(3 to 2, 2 to 1)
    assertEquals(
      3,
      MergedSemanticsTargeting.actionTarget(listOf(3), parents::get) { it == 3 || it == 2 },
    )
  }

  @Test
  fun actionTargetTriesCandidatesInOrderAndReturnsNullWhenNothingExposesIt() {
    val parents = mapOf(3 to 1, 5 to 4, 4 to 1)
    assertEquals(
      4,
      MergedSemanticsTargeting.actionTarget(listOf(3, 5), parents::get) { it == 4 },
    )
    assertNull(MergedSemanticsTargeting.actionTarget(listOf(3, 5), parents::get) { false })
  }

  private fun refOf(payload: ComposeSemanticsPayload, nodeId: String): String? {
    fun find(n: ComposeSemanticsNode): ComposeSemanticsNode? =
      if (n.nodeId == nodeId) n else n.children.firstNotNullOfOrNull(::find)
    return find(payload.root)?.ref
  }

  /** `Button(onClick) { Text("Example Button") }` as the unmerged tree shows it. */
  private fun unmergedButton(): ComposeSemanticsPayload =
    payload(
      node(
        "1",
        "0,0,200,200",
        children =
          listOf(
            node(
              "2",
              "10,10,150,50",
              role = "Button",
              clickable = true,
              children = listOf(node("3", "30,20,130,40", text = "Example Button")),
            )
          ),
      )
    )

  /** The same button in the merged tree: the text folded into the button, the child gone. */
  private fun mergedButton(): ComposeSemanticsPayload =
    payload(
      node(
        "1",
        "0,0,200,200",
        children =
          listOf(
            node(
              "2",
              "10,10,150,50",
              role = "Button",
              text = "Example Button",
              clickable = true,
            )
          ),
      )
    )

  private fun twoButtons(merged: Boolean): ComposeSemanticsPayload {
    fun button(id: Int, top: Int) =
      if (merged) {
        node(
          "$id",
          "10,$top,150,${top + 40}",
          role = "Button",
          text = "Example Button",
          clickable = true,
        )
      } else {
        node(
          "$id",
          "10,$top,150,${top + 40}",
          role = "Button",
          clickable = true,
          children =
            listOf(node("${id + 1}", "30,${top + 10},130,${top + 30}", text = "Example Button")),
        )
      }
    return payload(node("1", "0,0,200,200", children = listOf(button(2, 10), button(4, 100))))
  }

  private fun payload(root: ComposeSemanticsNode) =
    SemanticsRefs.assign(ComposeSemanticsPayload(root = root))

  private fun node(
    id: String,
    bounds: String,
    role: String? = null,
    text: String? = null,
    clickable: Boolean = false,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      role = role,
      text = text,
      label = text,
      clickable = clickable,
      children = children,
    )
}
