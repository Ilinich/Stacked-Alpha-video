package io.github.ilinich.stackedalphavideo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * A [TextureView] that plays a stacked-alpha video.
 *
 * ExoPlayer decodes into a `SurfaceTexture` owned by [StackedAlphaGlRenderer]; the renderer
 * composites RGB and matte into this view's own surface on a dedicated GL thread, one draw per
 * decoded frame. A `TextureView` rather than a `SurfaceView` is what makes the transparency real:
 * a `SurfaceView` is composited in its own layer behind the window, so its alpha reads as black
 * inside the Compose tree, while a `TextureView` is drawn as part of the hierarchy.
 */
@SuppressLint("ViewConstructor")
internal class StackedAlphaTextureView(
    context: Context,
    uri: String,
    private val state: StackedAlphaVideoState,
) : TextureView(context) {

    private val glThread = HandlerThread("StackedAlphaVideo-GL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private val renderer = StackedAlphaGlRenderer(
        glHandler = glHandler,
        onVideoSurfaceReady = { surface -> post { if (!released) player.setVideoSurface(surface) } },
        onFirstFrameDrawn = { post { state.isFirstFrameRendered = true } },
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        addListener(PlayerListener())
        prepare()
    }

    private var released = false
    private var playing = false
    private var visibleOnScreen = true

    init {
        isOpaque = false
        surfaceTextureListener = SurfaceListener()
    }

    fun applyPlayback(playing: Boolean, looping: Boolean, muted: Boolean) {
        if (released) return
        this.playing = playing
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        if (playing && player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        syncPlayWhenReady()
    }

    fun release() {
        if (released) return
        released = true
        player.release()
        glHandler.post { renderer.stop() }
        glThread.quitSafely()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Fires when the view or the window is hidden (screen off, app in background), so a
        // video nobody can see stops decoding.
        visibleOnScreen = isVisible
        syncPlayWhenReady()
    }

    private fun syncPlayWhenReady() {
        if (released) return
        player.playWhenReady = playing && visibleOnScreen
    }

    private inner class PlayerListener : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            state.onSourceSize(width = videoSize.width, height = videoSize.height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
                state.isPlaying = false
            }
        }
    }

    private inner class SurfaceListener : SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            glHandler.post { renderer.start(output = surface, width = width, height = height) }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            glHandler.post { renderer.resize(width = width, height = height) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            // Detach the decoder before the renderer releases the surface it writes into.
            if (!released) player.clearVideoSurface()
            glHandler.post { renderer.stop() }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }
}
