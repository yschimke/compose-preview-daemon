package ee.schimke.composeai.daemon.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkageReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * The build-wide player's fallback (compose-preview-daemon#341): an unpinned build whose default,
 * the embedded player, cannot link draws with the view player; a pinned one is refused by name.
 */
class RemoteComposeDefaultPlayerTest {
  private val view = RemoteComposePlayers.forKind(RemoteComposePlayerKind.VIEW)
  private val unlinked =
    RemoteComposeLinkageException(
      "Remote Compose player 'androidx-embedded'",
      RemoteComposeLinkageReport(
        1,
        1,
        listOf("ee/schimke/composeai/rcembedded/RcPlayerKt (class not found)"),
      ),
    )

  private fun load(kind: RemoteComposePlayerKind): RemoteComposePlayerBackend =
    if (kind == RemoteComposePlayerKind.EMBEDDED) throw unlinked else view

  @Test
  fun `an unpinned default that cannot link falls back to the view player, and says so`() {
    var reported: Pair<RemoteComposePlayerKind, RemoteComposePlayerKind>? = null
    val backend =
      RemoteComposePlayers.chooseDefault(
        RemoteComposePlayerKind.EMBEDDED,
        pinned = false,
        ::load,
      ) { kind, fallback, _ ->
        reported = kind to fallback
      }
    assertSame(view, backend)
    assertEquals(RemoteComposePlayerKind.EMBEDDED to RemoteComposePlayerKind.VIEW, reported)
  }

  @Test
  fun `a pinned player that cannot link is refused, never swapped`() {
    try {
      RemoteComposePlayers.chooseDefault(RemoteComposePlayerKind.EMBEDDED, pinned = true, ::load) {
        _,
        _,
        _ ->
        fail("a pinned player must not fall back")
      }
      fail("expected the linkage refusal")
    } catch (e: RemoteComposeLinkageException) {
      assertSame(unlinked, e)
    }
  }

  @Test
  fun `a default that links is used as is`() {
    var reported: RemoteComposePlayerKind? = null
    val backend =
      RemoteComposePlayers.chooseDefault(RemoteComposePlayerKind.VIEW, pinned = false, ::load) {
        kind,
        _,
        _ ->
        reported = kind
      }
    assertSame(view, backend)
    assertNull(reported)
  }

  @Test
  fun `when the fallback cannot link either, the default's own refusal is what surfaces`() {
    try {
      RemoteComposePlayers.chooseDefault(
        RemoteComposePlayerKind.EMBEDDED,
        pinned = false,
        {
          throw if (it == RemoteComposePlayerKind.EMBEDDED) unlinked
          else NoClassDefFoundError("view")
        },
      ) { _, _, _ ->
        fail("nothing was drawn with the fallback")
      }
      fail("expected the linkage refusal")
    } catch (e: RemoteComposeLinkageException) {
      assertSame(unlinked, e)
    }
  }
}
