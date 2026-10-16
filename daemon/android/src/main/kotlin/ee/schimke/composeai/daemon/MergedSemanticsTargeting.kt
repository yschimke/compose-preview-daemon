package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution

/**
 * Resolves interactive targets the way a person describes a control rather than the way Compose
 * splits it into nodes (compose-ai-tools#5945).
 *
 * A standard `Button(onClick = …) { Text("Example Button") }` is two semantics nodes in the
 * **unmerged** tree: the button carries `Role.Button` and `OnClick`, its child `Text` carries the
 * text. Likewise `FilledIconButton { Icon(contentDescription = "Settings Button") }` puts the
 * description on the child and `OnClick` on the parent. Matching only the unmerged tree therefore
 * never finds one node with both `role = Button` and `text = Example Button`, and the node that
 * does carry the description cannot be clicked. The **merged** tree is what accessibility services
 * see: the button node there carries its descendants' text and description.
 *
 * Both helpers only ever act when the unmerged lookup came up empty, so a target that already
 * resolved keeps resolving to the same node.
 */
internal object MergedSemanticsTargeting {

  /**
   * Resolve [target] against the [unmerged] payload first, exactly as before. When that finds
   * nothing and the target is a [SemanticsTarget.RoleText], retry against the [merged] tree, where
   * a merging node (a `Button`) carries its descendants' text and description.
   *
   * Refs and test tags are not retried: a ref is defined by the unmerged tree, and a test tag does
   * not merge into its ancestor, so the merged tree can only produce a different answer for them by
   * accident.
   *
   * Nodes coming back from the merged tree have their [ComposeSemanticsNode.ref] replaced with the
   * ref of the same node (matched by `nodeId`) in the unmerged tree, the tree `compose/semantics`
   * publishes and a [SemanticsTarget.Ref] resolves against, so an agent picking a candidate out of
   * an ambiguity report gets a handle that resolves. A merged node with no unmerged counterpart
   * carries no ref rather than one that names a different node.
   *
   * A merged-tree miss returns the original unmerged [TargetResolution.NotFound], so the candidate
   * list the caller reports is unchanged.
   */
  fun resolve(
    unmerged: ComposeSemanticsPayload,
    target: SemanticsTarget,
    merged: () -> ComposeSemanticsPayload?,
  ): TargetResolution {
    val first = SemanticsTargets.resolve(unmerged, target)
    if (first !is TargetResolution.NotFound || target !is SemanticsTarget.RoleText) return first
    val mergedPayload = merged() ?: return first
    val unmergedRefs = buildMap { unmerged.root.collectRefs(this) }
    fun ComposeSemanticsNode.withUnmergedRef() = copy(ref = unmergedRefs[nodeId])
    return when (val second = SemanticsTargets.resolve(mergedPayload, target)) {
      TargetResolution.NotFound -> first
      is TargetResolution.Resolved -> second.copy(node = second.node.withUnmergedRef())
      is TargetResolution.Ambiguous ->
        TargetResolution.Ambiguous(second.candidates.map { it.withUnmergedRef() })
    }
  }

  /**
   * The node an `a11y.action.*` should act on, given the nodes whose own content description
   * matched, in preference order.
   *
   * For each candidate in turn, the candidate itself if [exposes] holds, otherwise its nearest
   * ancestor (via [parent]) that does: the `Icon` carrying the description inside a
   * `FilledIconButton` hands the click to the button. The first candidate that yields a node wins,
   * so when the preferred candidate already exposes the action the result is unchanged. `null` when
   * no candidate nor any ancestor exposes it; the caller reports that as before.
   */
  fun <N : Any> actionTarget(candidates: List<N>, parent: (N) -> N?, exposes: (N) -> Boolean): N? =
    candidates.firstNotNullOfOrNull { candidate ->
      generateSequence(candidate, parent).firstOrNull(exposes)
    }

  private fun ComposeSemanticsNode.collectRefs(into: MutableMap<String, String>) {
    ref?.let { into.putIfAbsent(nodeId, it) }
    children.forEach { it.collectRefs(into) }
  }
}
