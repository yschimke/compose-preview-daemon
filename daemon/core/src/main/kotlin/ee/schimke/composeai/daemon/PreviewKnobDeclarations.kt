package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.data.overrides.PreviewOverrideType

/**
 * Turns a preview's **parameter knobs** into `PreviewOverrideDeclaration`s, so viewers draw their
 * controls the same way as for `previewOverride*` knobs (which declare themselves when read; a
 * parameter knob never announces itself during composition).
 *
 * A knob whose default discovery could not recover (an expression such as `stringResource(...)`) is
 * not declared: the declaration's `default` is non-null, and inventing one would show a false
 * default and a wrong "reset".
 *
 * `Long` and `Double` knobs are declared as text, as [PreviewOverrideType] has no wider numerics; a
 * text seed still parses against the knob's real kind, so only the control's shape differs.
 */
public object PreviewKnobDeclarations {

  /**
   * The declarations for [knobs] under [seeds]. `current` is resolved with the renderer's own
   * binding rules ([PreviewKnobSeeds.bind]) so it always matches the rendered pixels.
   */
  public fun of(
    knobs: List<PreviewKnobDto>,
    seeds: Map<String, PreviewOverrideValue>?,
  ): List<PreviewOverrideDeclaration> {
    if (knobs.isEmpty()) return emptyList()
    val bound = PreviewKnobSeeds.bind(knobs, seeds)
    return knobs.mapNotNull { knob ->
      val default = knob.default ?: return@mapNotNull null
      val type = declaredTypeOf(knob.type) ?: return@mapNotNull null
      // Null (or out of range when nothing bound) means "author default", as for the renderer.
      val seeded = bound.getOrNull(knob.index)
      PreviewOverrideDeclaration(
        key = knob.name,
        type = type,
        label = knob.name,
        default = valueOf(type, default),
        current = valueOf(type, seeded?.toString() ?: default),
        index = null,
        // Only enums carry options, and an enum cannot hold any other value.
        options = knob.options.map { PreviewOverrideOption(it) },
        optionsExhaustive = knob.options.isNotEmpty(),
      )
    }
  }

  /** The declaration type for a knob kind; null (undeclared) for a kind this does not know. */
  private fun declaredTypeOf(knobType: String): String? =
    when (knobType) {
      "STRING",
      "LONG",
      "DOUBLE",
      // The enum picker comes from `options`, as with `previewOverrideChoice`.
      "ENUM" -> PreviewOverrideType.STRING
      "BOOLEAN" -> PreviewOverrideType.BOOL
      "INT" -> PreviewOverrideType.INT
      "FLOAT" -> PreviewOverrideType.FLOAT
      else -> null
    }

  /** [text] as a value of [type]; unparseable text stays a string rather than becoming zero. */
  private fun valueOf(type: String, text: String): PreviewOverrideValue =
    when (type) {
      PreviewOverrideType.INT ->
        text.toIntOrNull()?.let { PreviewOverrideValue.IntValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      PreviewOverrideType.FLOAT ->
        text.toFloatOrNull()?.let { PreviewOverrideValue.FloatValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      PreviewOverrideType.BOOL ->
        text.toBooleanStrictOrNull()?.let { PreviewOverrideValue.BooleanValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      else -> PreviewOverrideValue.StringValue(text)
    }
}
