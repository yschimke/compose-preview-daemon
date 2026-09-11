# Avoid exceptions for unreadable captured fields

The semantics candidate C1 profile contains 10,331 inclusive samples in
`AccessibleObject.checkCanSetAccessible` under `ModifierTokenResolver.fieldValues`;
7,659 include `Throwable.fillInStackTrace`. These overlapping sample counts are
diagnostics, not additive predicted savings. The two-level captured-value scan
reaches JDK strings/boxed objects whose fields are encapsulated, repeatedly using
exceptions to return the expected “unreadable” result.

The experiment changes only that field-read loop to use `trySetAccessible()`.
The [Java 17 contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/reflect/AccessibleObject.html#trySetAccessible())
returns false when access cannot be enabled, while preserving the same enabled
access flag on success. Security exceptions and field-read failures remain caught.
There is no member cache or negative-result cache, so neither classloader lifetime
nor later module-opening behavior changes. Other reflection paths stay unchanged.

A regression case checks private app fields beside encapsulated JDK values and
keeps ordinary captured strings from being mistaken for placeholder chrome.
The formatter, 240 connector tests and 23 desktop integration tests pass.
The [smoke comparison](profiles/inspector-access-smoke-parity.json) matches all
three PNG/UIA frames and 33 data artifacts across eleven products, allowing only
two process-specific Typeface identities. Reload checks and measurements remain
pending. Compare against the frozen regex candidate to isolate the effect; do not
rebuild during the running regex benchmark. This is our inspector overhead, not
an upstream Robolectric bug.


The final repeated-run matrix compares production, semantics-plus-regex, and the
full candidate in three rotated sequential trials of red plus 60 dense renders.
It uses explicit density 2, default compilation and G1/1 GiB on JDK17 with GC
logging enabled equally for all variants. This provides both a direct production
comparison and an isolated field-access comparison, without adding percentages
from unrelated JVM profiles.
