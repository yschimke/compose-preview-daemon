package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.DataProductKey
import kotlinx.serialization.Serializable

// Accessibility data-product models, shared by the ATF integration, the daemon producer and
// readers of the JSON sidecars. The `renderer` package is kept for source compatibility.

/** ATF findings per preview, as written to `accessibility.json`. */
@Serializable
data class AccessibilityReport(
  val module: String,
  val entries: List<AccessibilityEntry>,
  /**
   * `null` for a normal run; e.g. `"atf-unavailable"` when empty [entries] must not read as clean
   * (constants defined in `:preview-data-api`'s `A11yWireFormat.kt`).
   */
  val status: String? = null,
  /**
   * `true` on a CLI report covering only some previews, where an absent id means "not checked".
   * Never set here, but declared so strict decoders accept the key; keep in step with
   * `A11yWireFormat.kt`.
   */
  val partial: Boolean = false,
)

@Serializable
data class AccessibilityEntry(
  val previewId: String,
  val findings: List<AccessibilityFinding>,
  /** Every accessibility-relevant node, findings or not, for a "what TalkBack sees" overlay. */
  val nodes: List<AccessibilityNode> = emptyList(),
  /**
   * Path, relative to `accessibility.json`, of the screenshot annotated with numbered findings.
   * Treat a missing file like `null`: fall back to the clean render.
   */
  val annotatedPath: String? = null,
)

/** One accessibility-relevant node: what TalkBack announces and what the overlay draws. */
@Serializable
data class AccessibilityNode(
  /**
   * The announced name: own description / text, else rolled up from merged descendants
   * ([AccessibilityLabels]). Empty means a real labelling bug.
   */
  val label: String,
  /**
   * Content-independent handle from [AccessibilityRefs] (role plus occurrence index): stable across
   * copy edits, moved by structural ones. `null` only on hand-built nodes and older files.
   */
  val ref: String? = null,
  /** TalkBack's class announcement (`Button`, `Image`, …); `null` for a plain `View`. */
  val role: String? = null,
  /**
   * Non-default flags (`clickable`, `disabled`, `checked`, …), the state description and a "hint:
   * <text>" entry. Heading is absent: ATF does not expose Compose's `heading()` reliably.
   */
  val states: List<String> = emptyList(),
  /**
   * `true` when this node is its own TalkBack focus stop; `false` under a focusable ancestor (the
   * `Text` inside a `Button`), which the overlay draws dashed.
   */
  val merged: Boolean = true,
  /**
   * `left,top,right,bottom` in source-bitmap pixels — same shape as
   * [AccessibilityFinding.boundsInScreen].
   */
  val boundsInScreen: String,
)

@Serializable
data class AccessibilityFinding(
  /** `ERROR`, `WARNING`, `INFO`, or `NOT_RUN` — upper-cased ATF `AccessibilityCheckResultType`. */
  val level: String,
  /** Short rule identifier — ATF check class simple name (e.g. `TouchTargetSizeCheck`). */
  val type: String,
  val message: String,
  /** Human-readable description of the offending element, if ATF could resolve one. */
  val viewDescription: String? = null,
  /** `left,top,right,bottom` in the preview's pixel space — agents can highlight on the PNG. */
  val boundsInScreen: String? = null,
)

@Serializable data class AccessibilityHierarchyPayload(val nodes: List<AccessibilityNode>)

@Serializable data class AccessibilityFindingsPayload(val findings: List<AccessibilityFinding>)

@Serializable
data class AccessibilityTouchTarget(
  val nodeId: String,
  val boundsInScreen: String,
  val widthDp: Float,
  val heightDp: Float,
  val findings: List<String>,
  val overlappingNodeIds: List<String>? = null,
)

@Serializable
data class AccessibilityTouchTargetsPayload(val targets: List<AccessibilityTouchTarget>)

@Serializable
data class AccessibilityOverlayArtifact(val path: String, val mediaType: String = "image/png")

object AccessibilityDataProducts {
  const val SCHEMA_VERSION: Int = 1
  const val KIND_HIERARCHY: String = "a11y/hierarchy"
  const val KIND_ATF: String = "a11y/atf"
  const val KIND_TOUCH_TARGETS: String = "a11y/touchTargets"
  const val KIND_OVERLAY: String = "a11y/overlay"

  val Hierarchy: DataProductKey<AccessibilityHierarchyPayload> =
    DataProductKey(KIND_HIERARCHY, SCHEMA_VERSION, AccessibilityHierarchyPayload::class.java)

  val Atf: DataProductKey<AccessibilityFindingsPayload> =
    DataProductKey(KIND_ATF, SCHEMA_VERSION, AccessibilityFindingsPayload::class.java)

  val TouchTargets: DataProductKey<AccessibilityTouchTargetsPayload> =
    DataProductKey(KIND_TOUCH_TARGETS, SCHEMA_VERSION, AccessibilityTouchTargetsPayload::class.java)

  val Overlay: DataProductKey<AccessibilityOverlayArtifact> =
    DataProductKey(KIND_OVERLAY, SCHEMA_VERSION, AccessibilityOverlayArtifact::class.java)
}
