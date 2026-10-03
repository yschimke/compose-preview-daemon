package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/** The colour [MainImmediateScopePreview] fills its frame with once it has composed. */
internal val MAIN_SCOPE_FILL = Color(0xFF2E7D32)

/**
 * A preview that takes `Dispatchers.Main.immediate` during composition — the shape of
 * `com.tunjid.treenav.compose.rememberNavigationEventStatus`, which every route of an app built on
 * that navigation library composes. A preview never asks for this explicitly; a library it renders
 * does, and on a classpath with no Main dispatcher the access throws mid-composition, leaving the
 * frame holding only whatever had drawn before it.
 */
@Suppress("unused") // invoked reflectively by the renderer
@Composable
fun MainImmediateScopePreview() {
  val scope = remember { CoroutineScope(Dispatchers.Main.immediate) }
  DisposableEffect(scope) { onDispose { scope.cancel() } }
  Box(Modifier.fillMaxSize().background(MAIN_SCOPE_FILL))
}
