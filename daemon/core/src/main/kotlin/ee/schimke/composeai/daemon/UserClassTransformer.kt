package ee.schimke.composeai.daemon

/**
 * Rewrites a user class's bytecode as [UserClassLoaderHolder]'s child loader defines it.
 *
 * The child is a plain [java.net.URLClassLoader] over the user module's own class directories, so
 * the classes it defines are never instrumented by anything else — not by Robolectric, whose
 * rewrites apply only to classes its sandbox loader defines. A behaviour the daemon needs inside
 * the user's own code therefore has to be applied here. The Android daemon uses it to pin the wall
 * clock the consumer's `System.currentTimeMillis()` reads (`ConsumerClockRewriter`).
 */
public interface UserClassTransformer {
  /**
   * Whether [className] (binary name, `a.b.C$D`) should be passed to [transform]. Asked before its
   * bytes are read, so a `false` costs nothing.
   */
  public fun appliesTo(className: String): Boolean

  /**
   * The bytes to define for [className]. Return [bytes] itself when there is nothing to change.
   * Must not throw for a well-formed class: a failure here fails the class load.
   */
  public fun transform(className: String, bytes: ByteArray): ByteArray
}
