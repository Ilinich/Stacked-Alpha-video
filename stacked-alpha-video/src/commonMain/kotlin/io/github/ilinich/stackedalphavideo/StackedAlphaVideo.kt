package io.github.ilinich.stackedalphavideo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays a stacked-alpha video: a regular video file whose top half holds the colour picture and
 * whose bottom half holds the alpha matte as grayscale. A shader samples both halves and draws the
 * colour with that alpha, so the picture is transparent wherever the matte is black.
 *
 * ```
 * ┌───────────┐
 * │    RGB    │  top half    → colour
 * ├───────────┤
 * │   alpha   │  bottom half → transparency (green channel)
 * └───────────┘
 * ```
 *
 * Any H.264 or HEVC file works, so one asset plays on every platform without codec-specific alpha
 * support. The composable fills the bounds given by [modifier]; to keep the source proportions use
 * `Modifier.aspectRatio(state.aspectRatio ?: 1f)`.
 *
 * Platform notes:
 * - **Android**: ExoPlayer decodes into an OpenGL ES texture on a `TextureView`, so the video is
 *   part of the view hierarchy and composes with anything drawn above or below it.
 * - **iOS**: AVPlayer frames are drawn with Metal on an `MTKView` placed as an overlay above the
 *   Compose canvas. Content behind the video shows through it, but Compose content cannot be
 *   drawn on top of the video area.
 *
 * @param uri location of the video: `file://`, `https://`, or a path returned by Compose
 * Resources' `Res.getUri(...)`.
 * @param modifier sizes and positions the video.
 * @param state playback state; see [rememberStackedAlphaVideoState].
 */
@Composable
public fun StackedAlphaVideo(
    uri: String,
    modifier: Modifier = Modifier,
    state: StackedAlphaVideoState = rememberStackedAlphaVideoState(),
) {
    PlatformStackedAlphaVideo(uri = uri, state = state, modifier = modifier)
}

@Composable
internal expect fun PlatformStackedAlphaVideo(
    uri: String,
    state: StackedAlphaVideoState,
    modifier: Modifier,
)
