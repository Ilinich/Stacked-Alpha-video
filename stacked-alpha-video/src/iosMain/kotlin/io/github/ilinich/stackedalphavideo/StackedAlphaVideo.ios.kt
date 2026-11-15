package io.github.ilinich.stackedalphavideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformStackedAlphaVideo(
    uri: String,
    state: StackedAlphaVideoState,
    modifier: Modifier,
) {
    val renderer = remember(uri, state) { StackedAlphaMetalRenderer(uri = uri, state = state) }

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    LaunchedEffect(renderer, state.isPlaying, state.isLooping, state.isMuted) {
        renderer.applyPlayback(
            playing = state.isPlaying,
            looping = state.isLooping,
            muted = state.isMuted,
        )
    }

    // By default Compose cuts the interop view's rect out of its own canvas, so whatever the
    // caller draws behind the video would be hidden and transparent pixels would show the window.
    // As an overlay the Metal layer sits above the canvas instead: its transparent pixels reveal
    // the Compose content underneath. The trade-off is that nothing can be drawn over the video.
    UIKitView(
        factory = { renderer.view },
        modifier = modifier,
        properties = UIKitInteropProperties(
            interactionMode = null,
            placedAsOverlay = true,
        ),
    )
}
