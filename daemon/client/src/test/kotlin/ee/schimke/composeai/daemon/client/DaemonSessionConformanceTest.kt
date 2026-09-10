package ee.schimke.composeai.daemon.client

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DaemonSession] must stay the whole of [DaemonClient]'s callable surface.
 *
 * The compiler already proves the easy direction — `DaemonClient` cannot claim to implement the
 * interface without supplying every method. This test proves the direction the compiler cannot:
 * that no *new* public method gets added to `DaemonClient` and left off the interface.
 *
 * That is the way an extracted interface actually decays. Nothing fails when it happens: the class
 * keeps working, its own consumers keep compiling, and the interface silently stops being a
 * complete description of a daemon — so the next embedder reaches past it for the concrete class
 * and we are back to where compose-ai-tools was, writing a 311-line adapter because the published
 * type did not say enough.
 */
class DaemonSessionConformanceTest {

  @Test
  fun `every public method on DaemonClient is declared on DaemonSession`() {
    val sessionMethods = signaturesOf(DaemonSession::class.java)
    val missing = signaturesOf(DaemonClient::class.java).filterNot { it in sessionMethods }.sorted()

    assertEquals(
      "These are public on DaemonClient but absent from DaemonSession. Either add them to the " +
        "interface, or make them non-public if they are not part of talking to a daemon:\n" +
        missing.joinToString("\n") { "  $it" },
      emptyList<String>(),
      missing,
    )
  }

  @Test
  fun `DaemonSession is what DaemonClient implements`() {
    assertTrue(
      "DaemonClient must implement DaemonSession",
      DaemonSession::class.java.isAssignableFrom(DaemonClient::class.java),
    )
  }

  /**
   * Not a runtime test — **this function existing and compiling is the assertion.**
   *
   * Moving the default parameter values onto [DaemonSession] removed `DaemonClient`'s own
   * `$default` bridges (19 of them; visible in the ABI dump). Source compatibility survives only
   * because Kotlin resolves an inherited default through the concrete type too. That is a language
   * guarantee rather than something we control, so it is worth pinning: if it ever stopped holding,
   * every caller that omits a `timeout` would break, and this file would stop compiling first.
   *
   * Both static types are exercised deliberately — the interface for new embedders, the class for
   * the existing callers that hold a `DaemonClient`.
   */
  @Suppress("unused", "UNUSED_PARAMETER")
  private fun defaultsRemainReachableFromBothStaticTypes(
    session: DaemonSession,
    client: DaemonClient,
  ) {
    session.extensionsList()
    session.renderNow(previews = listOf("a"))
    session.fileChanged(path = "x.kt")
    session.historyList()
    client.extensionsList()
    client.renderNow(previews = listOf("a"))
    client.fileChanged(path = "x.kt")
    client.historyList()
  }

  private fun signaturesOf(type: Class<*>): Set<String> =
    type.declaredMethods.filter { it.isCallableSurface() }.map { it.signature() }.toSet()

  /**
   * Public, non-synthetic, not inherited from [Any] or [java.io.Closeable].
   *
   * `$default` bridges are synthetic, so dropping synthetics is also what makes this comparison
   * survive default parameter values living on the interface rather than the class.
   */
  private fun Method.isCallableSurface(): Boolean =
    Modifier.isPublic(modifiers) &&
      !isSynthetic &&
      !isBridge &&
      name !in NOT_PROTOCOL &&
      declaringClass != Any::class.java

  private fun Method.signature(): String =
    "$name(${parameterTypes.joinToString(", ") { it.simpleName }})"

  private companion object {
    /**
     * `close` comes from `Closeable`, which both types have for their own reasons — the interface
     * because a session is a resource, the class because it owns a transport. It is not a protocol
     * method, so comparing it says nothing.
     */
    val NOT_PROTOCOL = setOf("close")
  }
}
