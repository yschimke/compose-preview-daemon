package ee.schimke.composeai.daemon

import org.robolectric.internal.bytecode.ClassDetails
import org.robolectric.internal.bytecode.ClassInstrumentor
import org.robolectric.internal.bytecode.ClassNodeProvider
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * Robolectric's [ClassInstrumentor], with duplicate local-variable rows removed from what it writes
 * (compose-ai-tools#6161).
 *
 * Instrumenting a constructor moves its code up to the `this(...)` / `super(...)` call into the new
 * `<init>` and leaves the old body's `LocalVariableTable` rows behind, pointing at labels that are
 * gone; ASM writes each of them as start 0, length 0. A Kotlin default-arguments constructor that
 * inlines the same getter twice before delegating — `Shapes(…)` in Wear Compose Material inlines
 * `.dp` for `4.dp` and `26.dp` — so ends up with two identical `$i$f$getDp` rows, and HotSpot
 * refuses to define the class. The jar's own bytes are valid (their rows start at different
 * offsets), which is why cleaning them before instrumentation is not enough: the sandbox defines
 * what this returns, so this is where [LocalVariableTables.dedupe] has to run.
 *
 * Bound in place of the stock instrumentor by [SandboxHoldingRunner]'s injector.
 */
class LocalVariableDedupingInstrumentor : ClassInstrumentor() {
  override fun instrument(
    classDetails: ClassDetails,
    config: InstrumentationConfiguration,
    classNodeProvider: ClassNodeProvider,
  ): ByteArray =
    LocalVariableTables.dedupe(super.instrument(classDetails, config, classNodeProvider))
}
