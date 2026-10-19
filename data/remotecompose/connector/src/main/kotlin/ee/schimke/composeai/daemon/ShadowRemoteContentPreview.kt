@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow that routes every call to upstream
 * `androidx.compose.remote.tooling.preview.RemoteContentPreview` through [RemoteOverridablePreview]
 * (compose-ai-tools#6116).
 *
 * ## Why the wrapper substitution is not enough
 *
 * [RemoteComposeWrapperSubstitution] swaps one class: a preview annotated
 * `@PreviewWrapper(RemotePreviewWrapper::class)`. A project's own `@PreviewWrapper` — one that
 * centres the content in a `RemoteBox`, provides design-system locals, or picks a non-default
 * `Profile` — and that then calls `RemoteContentPreview(profile = …)` itself, never reaches the
 * substitution. Its document was captured and played by the stock function, so no `.rc` bytes were
 * offered to [ee.schimke.composeai.data.render.IrSidecarChannel] (an empty
 * `compose/remotecompose-doc`), and playback skipped the daemon's player selection, knob capture
 * and clock control.
 *
 * Wrapper FQNs cannot enumerate those wrappers: every project names its own. The one thing they
 * share is the call they all end in, so this shadow intercepts the **callee**. That covers every
 * caller at once, whichever loader defined it — the user module's classes in the daemon's
 * hot-reload child loader resolve `RemoteContentPreviewKt` through the sandbox loader, the same as
 * a wrapper shipped in a library jar does — which a call-site rewrite (the
 * [UserClassTransformer][ee.schimke.composeai.daemon.UserClassTransformer] shape the consumer clock
 * uses) would not: that only sees classes the child loader defines.
 *
 * ## Why the replacement has the same shape
 *
 * Upstream is a `@Composable` function, so its JVM method is `RemoteContentPreview(Modifier,
 * Profile, Function2, Composer, int, int)`: the Compose compiler appends the composer, a `$changed`
 * mask and — because two parameters have defaults — a `$default` mask, and handles those defaults
 * inside the body rather than in a Kotlin `$default` synthetic. Robolectric hands a shadow the
 * original arguments verbatim, so [RemoteContentPreview] below is declared with the identical
 * source signature, defaults included: the Compose compiler then lowers it to the identical
 * descriptor and interprets the caller's masks exactly as upstream would. The defaults mirror
 * upstream's (`Modifier`, [RcPlatformProfiles.ANDROIDX]); a caller's own `Profile` passes through
 * untouched. `RemoteContentPreviewShadowTest` pins the descriptor against the upstream class so a
 * reshaped upstream fails a test rather than silently falling back to stock.
 *
 * ## Registration
 *
 * Referenced by class *name*, like the other connector shadows, so the annotation never forces
 * `remote-tooling-preview` to load. The daemon's `SandboxHoldingRunner` registers it, and
 * instruments exactly [TARGET_CLASS], only when that class is on the consumer's classpath; both
 * halves are needed, because Robolectric cannot shadow a class it did not instrument.
 * [RemoteOverridablePreview] never calls `RemoteContentPreview`, so the shadow cannot recurse.
 */
@Implements(className = ShadowRemoteContentPreview.TARGET_CLASS, isInAndroidSdk = false)
class ShadowRemoteContentPreview {
  companion object {
    /** The upstream file class whose `RemoteContentPreview` this shadow replaces. */
    const val TARGET_CLASS: String =
      "androidx.compose.remote.tooling.preview.RemoteContentPreviewKt"

    /**
     * Captures [content] against the caller's [profile], offers the document to the IR sidecar and
     * replays it through the build-wide player — [RemoteOverridablePreview], the same composable
     * [RemoteOverridablePreviewWrapper] forwards to.
     */
    @JvmStatic
    @Implementation
    @Composable
    fun RemoteContentPreview(
      modifier: Modifier = Modifier,
      profile: Profile = RcPlatformProfiles.ANDROIDX,
      content: @Composable @RemoteComposable () -> Unit,
    ) {
      RemoteOverridablePreview(profile = profile, modifier = modifier, content = content)
    }
  }
}
