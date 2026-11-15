package io.github.ilinich.stackedalphavideo

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackedAlphaVideoStateTest {

    private fun state() = StackedAlphaVideoState(playing = true, looping = true, muted = true)

    @Test
    fun visibleFrameIsHalfTheSourceHeight() {
        val state = state()

        state.onSourceSize(width = 720, height = 1440)

        assertEquals(IntSize(width = 720, height = 720), state.frameSize)
        assertEquals(1f, state.aspectRatio)
    }

    @Test
    fun sizeIsUnknownUntilReported() {
        val state = state()

        assertNull(state.frameSize)
        assertNull(state.aspectRatio)
    }

    @Test
    fun emptySizeIsIgnored() {
        val state = state()
        state.onSourceSize(width = 320, height = 640)

        state.onSourceSize(width = 0, height = 0)

        assertEquals(IntSize(width = 320, height = 320), state.frameSize)
    }

    @Test
    fun togglePlaybackFlipsIsPlaying() {
        val state = state()

        state.togglePlayback()
        assertFalse(state.isPlaying)

        state.togglePlayback()
        assertTrue(state.isPlaying)
    }
}
