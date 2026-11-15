package io.github.ilinich.stackedalphavideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal actual fun PlatformStackedAlphaVideo(
    uri: String,
    state: StackedAlphaVideoState,
    modifier: Modifier,
) {
    // A new source or a new state object means a new player: recreate the view rather than
    // rewire a live one.
    key(uri, state) {
        AndroidView(
            factory = { context -> StackedAlphaTextureView(context = context, uri = uri, state = state) },
            modifier = modifier,
            update = { view ->
                view.applyPlayback(
                    playing = state.isPlaying,
                    looping = state.isLooping,
                    muted = state.isMuted,
                )
            },
            onRelease = { view -> view.release() },
        )
    }
}
