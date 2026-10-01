package ee.schimke.composeai.daemon.remotecompose.fixture

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayerBackend

/**
 * A player registered the way one outside this connector would be — through
 * `META-INF/services/…RemoteComposePlayerBackend` in its own jar — so the registry and the wire's
 * `playerId` are tested against the extension point itself, not only the built-ins.
 */
class FixturePlayerBackend : RemoteComposePlayerBackend {
  override val id: String = "fixture-player"

  override val aliases: Set<String> = setOf("fixture")

  override val linkedPackages: List<String> =
    listOf("ee.schimke.composeai.daemon.remotecompose.fixture")

  @Composable
  override fun Play(
    document: RemoteComposeDocumentSource,
    namedValues: Map<String, RemoteNamedValue>,
    modifier: Modifier,
  ) {}
}
