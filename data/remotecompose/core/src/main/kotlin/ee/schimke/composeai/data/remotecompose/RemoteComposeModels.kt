package ee.schimke.composeai.data.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import kotlinx.serialization.Serializable

/**
 * Identity of the `compose/remotecompose` data product. Lives in this alpha-free module so clients
 * can use the schema without the registry or `androidx.compose.remote.*`.
 */
public object RemoteComposeProduct {
  public const val KIND: String = "compose/remotecompose"
  // v2 added [RemoteComposePayload.declarations]; additive, so v1 readers still decode it.
  public const val SCHEMA_VERSION: Int = 2
}

/**
 * Identity of the `compose/remotecompose-doc` data product: the serialized `.rc` document the
 * latest render captured, as opposed to [RemoteComposeProduct]'s editable knob state.
 */
public object RemoteComposeDocumentProduct {
  public const val KIND: String = "compose/remotecompose-doc"
  public const val SCHEMA_VERSION: Int = 1
}

/** `compose/remotecompose-doc` payload: the captured `.rc` bytes, Base64-encoded. */
public @Serializable data class RemoteComposeDocumentPayload(val documentBase64: String)

/**
 * An editable named-value knob declared during render (by reading a named value through
 * `LocalRemoteComposeHost`, or `declareKnob`); the Remote Compose counterpart of
 * [ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration]. [name] is the key overrides
 * address; [default]'s variant gives the knob's kind.
 */
@Serializable
public data class RemoteComposeKnobDeclaration(val name: String, val default: RemoteNamedValue)

/**
 * The `<stem>.remotecompose.json` render sidecar: only the declared knobs, unlike the live
 * [RemoteComposePayload]. Copied into bundles verbatim.
 */
@Serializable
public data class RemoteComposeDeclarationsPayload(
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList()
) {
  /**
   * The player that drew this capture (`"androidx-embedded"` / `"androidx-view"`), recorded because
   * it cannot be derived from the preview. Legacy captures say `"cmp-android"` (embedded) or
   * `"java"` (view). Null means unrecorded or more than one player, never "the default".
   *
   * A body property, not a constructor parameter, so the published `<init>(List)` and `copy(List)`
   * signatures survive for already-compiled consumers.
   */
  var capturePlayer: String? = null
}

/**
 * `compose/remotecompose` payload.
 *
 * @property namedValues effective values after the render: override seeds plus user write-backs.
 * @property hostActions `HostAction`s fired, oldest first, capped at [HOST_ACTION_BUFFER_SIZE].
 * @property profile the bound platform profile, if any.
 * @property declarations the editable knobs (name, default, kind), deduped, in declaration order.
 */
@Serializable
public data class RemoteComposePayload(
  val namedValues: Map<String, RemoteNamedValue> = emptyMap(),
  val hostActions: List<RemoteHostAction> = emptyList(),
  val profile: RemoteComposeProfile? = null,
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList(),
) {
  public companion object {
    /** Host-action ring-buffer size; subscribers wanting history accumulate it themselves. */
    public const val HOST_ACTION_BUFFER_SIZE: Int = 256
  }
}
