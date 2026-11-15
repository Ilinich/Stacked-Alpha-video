package io.github.ilinich.stackedalphavideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

/**
 * Playback state of a [StackedAlphaVideo].
 *
 * The caller drives [isPlaying], [isLooping] and [isMuted]; the player reports back [frameSize]
 * and [isFirstFrameRendered]. Create it with [rememberStackedAlphaVideoState].
 */
@Stable
public class StackedAlphaVideoState internal constructor(
    playing: Boolean,
    looping: Boolean,
    muted: Boolean,
) {

    /** Whether the video should be playing. Set to `false` by the player when a non-looping video ends. */
    public var isPlaying: Boolean by mutableStateOf(playing)

    /** Whether the video starts over when it reaches the end. */
    public var isLooping: Boolean by mutableStateOf(looping)

    /** Whether the audio track, if the file has one, is silenced. */
    public var isMuted: Boolean by mutableStateOf(muted)

    /**
     * Size of the picture the user sees: the source width and half the source height, since the
     * other half of every frame is the alpha matte. `null` until the player knows the video size.
     */
    public var frameSize: IntSize? by mutableStateOf(null)
        internal set

    /** `true` once the first frame has been drawn. Useful to hide a placeholder. */
    public var isFirstFrameRendered: Boolean by mutableStateOf(false)
        internal set

    /** Width divided by height of [frameSize], or `null` while the size is unknown. */
    public val aspectRatio: Float?
        get() = frameSize?.let { size -> if (size.height > 0) size.width.toFloat() / size.height else null }

    public fun play() {
        isPlaying = true
    }

    public fun pause() {
        isPlaying = false
    }

    public fun togglePlayback() {
        isPlaying = !isPlaying
    }

    /** Called by the platform player with the decoded size, which includes both halves. */
    internal fun onSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val visible = IntSize(width = width, height = height / 2)
        if (frameSize != visible) frameSize = visible
    }
}

/**
 * Creates and remembers a [StackedAlphaVideoState].
 *
 * @param playing whether playback starts right away.
 * @param looping whether the video restarts when it ends.
 * @param muted whether the audio track is silenced. Stacked videos are usually silent animations,
 * so this defaults to `true`.
 */
@Composable
public fun rememberStackedAlphaVideoState(
    playing: Boolean = true,
    looping: Boolean = true,
    muted: Boolean = true,
): StackedAlphaVideoState = remember {
    StackedAlphaVideoState(playing = playing, looping = looping, muted = muted)
}
