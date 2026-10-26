package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget

/**
 * Turns a `record_preview` script timeline into a runnable Compose UI test (issue #1786) — the
 * Compose analogue of Playwright's `codegen`. The recorded exploration becomes durable regression
 * coverage: each pointer event that carried a semantic target (issue #1784) emits a stable
 * `onNodeWith…().performClick()` step rather than a pixel coordinate, and each `recording.probe`
 * marker becomes an assertion.
 *
 * **Probe assertions.** When a probe carried a [RecordingProbeNode] snapshot of the live semantics
 * (captured host-side from the same tree target resolution walks), the generator diffs it against
 * the previous probe's snapshot and emits the strongest stable assertion it can: a node that
 * appeared since the last probe becomes `assertExists()`, one that disappeared becomes
 * `assertDoesNotExist()`, and the anchors present at the first probe are asserted to exist. Probes
 * without a snapshot (older daemons, or a probe that captured nothing assertable) fall back to a
 * labelled `// TODO assert state` stub so the spot is still marked.
 *
 * **Gestures.** `input.rotaryScroll` becomes `performRotaryScrollInput {
 * rotateToScrollVertically(…) }` with the recorded `scrollDeltaY` as the pixel distance, the unit
 * the daemon dispatches it in (issue #398). A touch drag — `input.pointerDown`, any
 * `input.pointerMove`s, `input.pointerUp` — becomes one `performTouchInput { down(…); moveTo(…); …;
 * up() }` block, with the gaps between the recorded events kept as event time so a swipe's velocity
 * survives. Recorded pixel coordinates are root-relative, so a pixel gesture runs on `onRoot()`;
 * one recorded against a single semantic target runs on that node at its centre. A gesture that
 * moves between targets, or mixes targets and pixels, is left as a comment rather than replayed as
 * a different interaction, as are mouse gestures and key events.
 *
 * Steps are built from what the recording **actually did**: a [Step] whose `applied` is false (the
 * daemon reported `unsupported` script evidence — e.g. a target that matched no node) is emitted as
 * a skipped-step comment, never a `performClick`, so the generated test reflects the captured flow.
 *
 * Pure string generation — no Compose / daemon runtime — so it is golden-testable in isolation.
 */
public object RecordingTestGenerator {

  /**
   * One recorded event plus whether the recording reported it as applied (vs `unsupported`).
   * [probeSemantics] is the host-captured semantics snapshot for a `recording.probe` event (null
   * for every other kind, and for probes from daemons that predate the capture).
   */
  public data class Step(
    val event: RecordingScriptEvent,
    val applied: Boolean = true,
    val probeSemantics: List<RecordingProbeNode>? = null,
  )

  /** Inputs for one generated test. */
  public data class Spec(
    /** Generated class name, e.g. `GeneratedTogglePreviewTest`. */
    val className: String,
    /** Generated `@Test` method name, e.g. `togglePreviewInteraction`. */
    val methodName: String,
    /** Composable call placed in `setContent { … }`, e.g. `TogglePreview()`. */
    val composableInvocation: String,
    /** Optional package declaration for the generated file. */
    val packageName: String? = null,
    val steps: List<Step>,
  )

  /** Wrap raw events as all-applied steps — for callers without recording evidence. */
  public fun stepsOf(events: List<RecordingScriptEvent>): List<Step> = events.map { Step(it) }

  /**
   * Build a [Spec] for a recording captured against [previewId] (issue #2047, the record-live
   * bridge), deriving sensible identifiers so a client holding only the captured timeline (the VS
   * Code Record toggle) gets a compilable test without authoring names by hand.
   *
   * [functionName] / [classFqn] come from the daemon's preview catalog when available —
   * load-bearing for named/variant previews, whose synthetic id (`…WeatherForecast_Light`) is not
   * the function name. When the id isn't in the catalog, the base name is sanitised out of
   * [previewId]. Any `*Override` wins over the derived default, so a caller that knows better can
   * pin exact names. Events are wrapped as all-applied steps (panel clicks dispatched); callers
   * with per-event evidence build [Step]s themselves and call [generate] directly.
   */
  public fun defaultSpec(
    previewId: String,
    functionName: String?,
    classFqn: String?,
    events: List<RecordingScriptEvent>,
    classNameOverride: String? = null,
    methodNameOverride: String? = null,
    composableInvocationOverride: String? = null,
    packageNameOverride: String? = null,
  ): Spec {
    val base = sanitizeIdentifier(functionName ?: previewId.substringAfterLast('.')) ?: "Preview"
    val pascal = base.replaceFirstChar { it.uppercaseChar() }
    val camel = base.replaceFirstChar { it.lowercaseChar() }
    val derivedPackage =
      classFqn?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    return Spec(
      className = classNameOverride?.takeIf { it.isNotBlank() } ?: "Generated${pascal}Test",
      methodName = methodNameOverride?.takeIf { it.isNotBlank() } ?: "${camel}Interaction",
      composableInvocation = composableInvocationOverride?.takeIf { it.isNotBlank() } ?: "$base()",
      packageName = packageNameOverride?.takeIf { it.isNotBlank() } ?: derivedPackage,
      steps = stepsOf(events),
    )
  }

  /**
   * Reduce an arbitrary string to a legal Kotlin identifier (letters/digits/underscore, not
   * starting with a digit) or `null` when nothing usable remains. Strips the variant suffix marker
   * (`#…`) and any FQN/package punctuation a synthetic preview id carries.
   */
  private fun sanitizeIdentifier(raw: String): String? {
    val cleaned =
      raw.substringAfterLast('.').substringBefore('#').filter { it.isLetterOrDigit() || it == '_' }
    val trimmed = cleaned.trimStart { it.isDigit() }
    return trimmed.takeIf { it.isNotBlank() }
  }

  public fun generate(spec: Spec): String = buildString {
    val sortedSteps = spec.steps.sortedBy { it.event.tMs }
    // Probe snapshots opt the assertion finders/imports in; without any, the output stays
    // byte-identical to the pre-#1786-assertions stub form so older recordings are unaffected.
    val hasProbeAssertions = sortedSteps.any {
      it.event.kind == "recording.probe" && it.probeSemantics != null
    }
    // Render the body first: which gesture APIs it used decides the imports and the opt-in, and a
    // recording without gestures keeps its pre-#403 output byte for byte.
    val usage = GestureUsage()
    val body = buildString { appendSteps(sortedSteps, usage) }
    spec.packageName?.takeIf { it.isNotBlank() }?.let { appendLine("package $it").appendLine() }
    if (usage.offset) appendLine("import androidx.compose.ui.geometry.Offset")
    appendLine("import androidx.compose.ui.test.junit4.createComposeRule")
    if (usage.rotary) appendLine("import androidx.compose.ui.test.ExperimentalTestApi")
    if (hasProbeAssertions) {
      appendLine("import androidx.compose.ui.test.assertDoesNotExist")
      appendLine("import androidx.compose.ui.test.assertExists")
      appendLine("import androidx.compose.ui.test.onNodeWithContentDescription")
    }
    appendLine("import androidx.compose.ui.test.onNodeWithTag")
    appendLine("import androidx.compose.ui.test.onNodeWithText")
    if (usage.root) appendLine("import androidx.compose.ui.test.onRoot")
    appendLine("import androidx.compose.ui.test.performClick")
    if (usage.rotary) appendLine("import androidx.compose.ui.test.performRotaryScrollInput")
    if (usage.touch) appendLine("import androidx.compose.ui.test.performTouchInput")
    appendLine("import org.junit.Rule")
    appendLine("import org.junit.Test")
    appendLine()
    appendLine("// Generated by compose-preview from a record_preview interaction (issue #1786).")
    appendLine(
      "// Confirm the setContent call below and add the composable's import before running"
    )
    appendLine("// (named/variant previews share their base @Composable function).")
    if (hasProbeAssertions) {
      appendLine("// Probe assertions are inferred from the captured semantics — review them.")
    } else {
      appendLine("// Fill in the assertions marked TODO at each recording.probe marker.")
    }
    appendLine("class ${spec.className} {")
    appendLine()
    appendLine("  @get:Rule val composeTestRule = createComposeRule()")
    appendLine()
    appendLine("  @Test")
    // `performRotaryScrollInput` is still `@ExperimentalTestApi`.
    if (usage.rotary) appendLine("  @OptIn(ExperimentalTestApi::class)")
    appendLine("  fun ${spec.methodName}() {")
    appendLine("    composeTestRule.setContent { ${spec.composableInvocation} }")
    append(body)
    appendLine("  }")
    append("}")
    appendLine()
  }

  /** Which optional test APIs the generated body uses — each pulls in its own imports. */
  private class GestureUsage {
    var touch = false
    var rotary = false
    var root = false
    var offset = false
  }

  private fun StringBuilder.appendSteps(sortedSteps: List<Step>, usage: GestureUsage) {
    // The previous probe's snapshot — diffed against the next probe to detect appeared/disappeared
    // nodes. Null until the first probe with a snapshot is seen.
    var previousProbe: List<RecordingProbeNode>? = null
    val gestures = touchGestures(sortedSteps)
    var index = 0
    while (index < sortedSteps.size) {
      val gesture = gestures[index]
      if (gesture != null) {
        // Consecutive events of one gesture share a `performTouchInput` block. A step in between
        // (a probe, say) splits the block, but not the gesture: its timing and pointer position
        // carry over to the block that continues it.
        var end = index
        while (end + 1 < sortedSteps.size && gestures[end + 1] === gesture) end++
        appendTouchGesture(sortedSteps.subList(index, end + 1).map { it.event }, gesture, usage)
        index = end + 1
        continue
      }
      val step = sortedSteps[index]
      appendStep(step, previousProbe, usage)
      if (step.event.kind == "recording.probe" && step.probeSemantics != null) {
        previousProbe = step.probeSemantics
      }
      index++
    }
  }

  /**
   * One recorded touch gesture: from a `pointerDown` (or the first stray touch event) to its
   * `pointerUp`, possibly interrupted by other steps. [selector] is the node it runs on —
   * `onRoot()` for a pixel gesture — or null when it cannot be replayed faithfully. [lastTMs] /
   * [lastPosition] carry the pointer's state from one emitted block to the next.
   */
  private class TouchGesture(val selector: String?, val pixels: Boolean) {
    var lastTMs: Long? = null
    var lastPosition: String? = null
  }

  /** Assign each generated touch step its [TouchGesture]; null for every other step. */
  private fun touchGestures(sortedSteps: List<Step>): List<TouchGesture?> {
    val memberIndexes = mutableListOf<MutableList<Int>>()
    var open: MutableList<Int>? = null
    sortedSteps.forEachIndexed { i, step ->
      if (!isGeneratedTouchStep(step)) return@forEachIndexed
      if (open == null || step.event.kind == POINTER_DOWN) {
        open = mutableListOf<Int>().also(memberIndexes::add)
      }
      open!!.add(i)
      if (step.event.kind == POINTER_UP) open = null
    }
    val result = arrayOfNulls<TouchGesture>(sortedSteps.size)
    for (members in memberIndexes) {
      val events = members.map { sortedSteps[it].event }
      val gesture =
        TouchGesture(selector = gestureSelector(events), pixels = events.all(::hasPixels))
      members.forEach { result[it] = gesture }
    }
    return result.toList()
  }

  private fun hasPixels(event: RecordingScriptEvent): Boolean =
    event.pixelX != null && event.pixelY != null

  /**
   * Where a gesture can be replayed. Recorded pixels are root-relative, so a gesture whose every
   * event has them runs on `onRoot()`. A gesture recorded against a semantic target runs at that
   * node's centre — but only when every event names the same node: the daemon resolved each event
   * against its own target, so one that moves between nodes (or mixes nodes and pixels) is a path
   * across the screen that a single node's `center` cannot reproduce. Null leaves it to be replayed
   * by hand rather than generating a different interaction.
   */
  private fun gestureSelector(events: List<RecordingScriptEvent>): String? {
    if (events.all(::hasPixels)) return "onRoot()"
    if (events.any(::hasPixels)) return null
    val selectors = events.map { nodeSelector(it.target) }
    val first = selectors.first() ?: return null
    return first.takeIf { selectors.all { it == first } }
  }

  /**
   * An applied touch event the generator turns into `performTouchInput`. Mouse gestures (the daemon
   * routes `pointerType = "mouse"` through `performMouseInput`) stay comments for now.
   */
  private fun isGeneratedTouchStep(step: Step): Boolean =
    step.applied &&
      step.event.kind in TOUCH_KINDS &&
      step.event.pointerType?.trim()?.lowercase() != "mouse"

  /**
   * One `performTouchInput { … }` block for (part of) a recorded touch [gesture]. The recorded gaps
   * become event time (`moveTo(…, delayMillis = gap)` / `advanceEventTime(gap)`), the same spacing
   * the daemon dispatched them at (issue #399), so `VelocityTracker` sees the recorded swipe — and
   * a hold that a probe interrupted keeps its length.
   */
  private fun StringBuilder.appendTouchGesture(
    events: List<RecordingScriptEvent>,
    gesture: TouchGesture,
    usage: GestureUsage,
  ) {
    val selector = gesture.selector
    if (selector == null) {
      events.forEach {
        appendLine(
          "    // ${it.kind} ${describeTarget(it)} — gesture has no single node or full pixel " +
            "path to replay; drive it by hand"
        )
      }
      return
    }
    usage.touch = true
    if (gesture.pixels) usage.root = true
    appendLine("    composeTestRule.$selector.performTouchInput {")
    for (event in events) {
      val gap = gesture.lastTMs?.let { (event.tMs - it).coerceAtLeast(0L) } ?: 0L
      gesture.lastTMs = event.tMs
      val position =
        if (gesture.pixels) {
          usage.offset = true
          "Offset(${event.pixelX}f, ${event.pixelY}f)"
        } else "center"
      when (event.kind) {
        POINTER_DOWN -> {
          if (gap > 0L) appendLine("      advanceEventTime($gap)")
          appendLine("      down($position)")
        }
        POINTER_MOVE -> appendLine("      moveTo($position, delayMillis = $gap)")
        POINTER_UP -> {
          // Like the daemon, release where the event says, moving there first only if needed.
          if (position != gesture.lastPosition && gesture.lastPosition != null) {
            appendLine("      moveTo($position, delayMillis = $gap)")
          } else if (gap > 0L) {
            appendLine("      advanceEventTime($gap)")
          }
          appendLine("      up()")
        }
      }
      gesture.lastPosition = position
    }
    appendLine("    }")
  }

  private fun StringBuilder.appendStep(
    step: Step,
    previousProbe: List<RecordingProbeNode>?,
    usage: GestureUsage,
  ) {
    val event = step.event
    when {
      (event.kind == "input.click" || event.kind == ROTARY_SCROLL || event.kind in TOUCH_KINDS) &&
        !step.applied ->
        appendLine(
          "    // ${event.kind} ${describeTarget(event)} — not resolved during the recording; " +
            "step skipped"
        )
      event.kind == "input.click" -> appendLine("    ${clickStep(event)}")
      event.kind == ROTARY_SCROLL -> appendRotaryScroll(event, usage)
      event.kind == "recording.probe" -> appendProbe(step, previousProbe)
      else ->
        appendLine(
          "    // ${event.kind}${event.label?.let { " \"$it\"" } ?: ""} — not yet generated; " +
            "drive it by hand"
        )
    }
  }

  /**
   * `input.rotaryScroll` → `performRotaryScrollInput`. `scrollDeltaY` is a pixel distance on the
   * daemon (issue #398), and `rotateToScrollVertically` takes the same unit with the same sign. The
   * daemon sends the event to whatever is focused under the pointer; a test sends it to the node
   * that owns rotary focus, so a recorded target is used when there is one and the root otherwise.
   */
  private fun StringBuilder.appendRotaryScroll(event: RecordingScriptEvent, usage: GestureUsage) {
    val delta = event.scrollDeltaY
    if (delta == null || delta == 0f) {
      appendLine("    // ${event.kind} — no scrollDeltaY recorded; nothing to replay")
      return
    }
    val target =
      nodeSelector(event.target)
        ?: run {
          usage.root = true
          "onRoot()"
        }
    usage.rotary = true
    appendLine(
      "    composeTestRule.$target.performRotaryScrollInput { " +
        "rotateToScrollVertically(${delta.toKotlinFloatLiteral()}) }"
    )
  }

  private fun Float.toKotlinFloatLiteral(): String =
    if (this == kotlin.math.floor(this) && kotlin.math.abs(this) < 1e7f) "${toLong()}f"
    else "${this}f"

  private fun clickStep(event: RecordingScriptEvent): String {
    val selector = nodeSelector(event.target)
    if (selector != null) return "composeTestRule.$selector.performClick()"
    val x = event.pixelX
    val y = event.pixelY
    return if (x != null && y != null) {
      "// click at ($x, $y) — re-record with a testTag/role target for a stable, " +
        "coordinate-free step"
    } else {
      "// click — no target or coordinates recorded; cannot generate a step"
    }
  }

  /** Map a semantic target onto the strongest available Compose test finder. */
  private fun nodeSelector(target: SemanticsInputTarget?): String? {
    if (target == null) return null
    target.testTag
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithTag(${it.quote()})"
      }
    target.text
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithText(${it.quote()})"
      }
    // ref / role alone have no direct Compose-test finder; surface them so the author can refine.
    return null
  }

  private fun describeTarget(event: RecordingScriptEvent): String {
    val t = event.target ?: return "(no target)"
    return listOfNotNull(
        t.testTag?.let { "testTag=$it" },
        t.role?.let { "role=$it" },
        t.text?.let { "text=\"$it\"" },
        t.ref?.let { "ref=$it" },
      )
      .joinToString(" ")
      .ifBlank { "(no target)" }
  }

  /**
   * Emit the assertion block for one `recording.probe`. With a semantics snapshot, diff it against
   * [previousProbe] and emit `assertExists()` for nodes that appeared (or, at the first probe, the
   * anchors present) and `assertDoesNotExist()` for nodes that disappeared. Without a snapshot — or
   * when the diff yields nothing assertable — fall back to the `// TODO assert state` stub so the
   * probe is still visible in the generated test.
   */
  private fun StringBuilder.appendProbe(step: Step, previousProbe: List<RecordingProbeNode>?) {
    val label = step.event.label?.takeIf { it.isNotBlank() }
    val labelSuffix = label?.let { " at probe \"$it\"" } ?: ""
    val snapshot = step.probeSemantics
    if (snapshot == null) {
      appendLine("    // TODO assert state$labelSuffix")
      return
    }
    val current = snapshot.mapNotNull { node -> nodeFinder(node)?.let { it to node } }
    val assertions = buildList {
      if (previousProbe == null) {
        // First probe: no prior snapshot to diff against, so assert that the interaction
        // anchors
        // (test-tagged or clickable nodes) are present — the stable handles worth pinning.
        current
          .filter { (_, node) -> node.testTag != null || node.clickable }
          .forEach { (finder, _) -> add("composeTestRule.$finder.assertExists()") }
      } else {
        val before = previousProbe.mapNotNull(::nodeFinder).toSet()
        val after = current.map { it.first }.toSet()
        current
          .filter { (finder, _) -> finder !in before }
          .forEach { (finder, _) -> add("composeTestRule.$finder.assertExists()") }
        (before - after).forEach { finder -> add("composeTestRule.$finder.assertDoesNotExist()") }
      }
    }
      .distinct()
    if (assertions.isEmpty()) {
      appendLine("    // TODO assert state$labelSuffix")
      return
    }
    val probeName = label?.let { "probe \"$it\"" } ?: "probe"
    appendLine("    // $probeName — assertions inferred from captured semantics")
    assertions.forEach { appendLine("    $it") }
  }

  /** Strongest stable Compose-test finder for a captured probe node, or null when it has none. */
  private fun nodeFinder(node: RecordingProbeNode): String? {
    node.testTag
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithTag(${it.quote()})"
      }
    node.text
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithText(${it.quote()})"
      }
    node.contentDescription
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "onNodeWithContentDescription(${it.quote()})"
      }
    return null
  }

  private const val POINTER_DOWN = "input.pointerDown"
  private const val POINTER_MOVE = "input.pointerMove"
  private const val POINTER_UP = "input.pointerUp"
  private const val ROTARY_SCROLL = "input.rotaryScroll"
  private val TOUCH_KINDS = setOf(POINTER_DOWN, POINTER_MOVE, POINTER_UP)

  private fun String.quote(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
