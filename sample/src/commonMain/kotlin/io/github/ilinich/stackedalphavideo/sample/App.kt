package io.github.ilinich.stackedalphavideo.sample

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ilinich.stackedalphavideo.StackedAlphaVideo
import io.github.ilinich.stackedalphavideo.StackedAlphaVideoState
import io.github.ilinich.stackedalphavideo.rememberStackedAlphaVideoState
import org.jetbrains.compose.resources.ExperimentalResourceApi
import stacked_alpha_video.sample.generated.resources.Res

private enum class DemoVideo(val title: String, val file: String) {
    Chest(title = "Chest", file = "files/chest.mp4"),
    Bubble(title = "Bubble", file = "files/bubble.mp4"),
    Gems(title = "Gems", file = "files/gems.mp4"),
}

private enum class Backdrop(val title: String) {
    None("None"),
    Checkerboard("Checkerboard"),
    Gradient("Gradient"),
    Stripes("Stripes"),
}

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var video by rememberSaveable { mutableStateOf(DemoVideo.Chest) }
        var backdrop by rememberSaveable { mutableStateOf(Backdrop.Checkerboard) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E0B16))
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            // A new state per video: playback flags reset when the source changes.
            key(video) {
                val state = rememberStackedAlphaVideoState()
                Stage(
                    video = video,
                    backdrop = backdrop,
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                PlaybackControls(state = state)
            }

            ChipRow(
                label = "Video",
                options = DemoVideo.entries,
                selected = video,
                title = { it.title },
                onSelect = { video = it },
            )
            ChipRow(
                label = "Behind the video",
                options = Backdrop.entries,
                selected = backdrop,
                title = { it.title },
                onSelect = { backdrop = it },
            )
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "Stacked Alpha Video",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "One MP4: colour on top, alpha matte below. The shader joins them into a transparent picture.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
        )
    }
}

/** The video over a moving backdrop: if the backdrop shows through, the alpha is real. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun Stage(
    video: DemoVideo,
    backdrop: Backdrop,
    state: StackedAlphaVideoState,
    modifier: Modifier = Modifier,
) {
    val uri = remember(video) { Res.getUri(video.file) }
    Box(
        modifier = modifier.clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedBackdrop(backdrop = backdrop, modifier = Modifier.fillMaxSize())
        StackedAlphaVideo(
            uri = uri,
            state = state,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(state.aspectRatio ?: 1f),
        )
    }
}

@Composable
private fun PlaybackControls(state: StackedAlphaVideoState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(text = if (state.isPlaying) "Pause" else "Play", selected = state.isPlaying) {
            state.togglePlayback()
        }
        ToggleChip(text = "Loop", selected = state.isLooping) {
            state.isLooping = !state.isLooping
        }
        Spacer(Modifier.weight(1f))
        val size = state.frameSize
        if (size != null) {
            Text(
                text = "${size.width}×${size.height}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                ToggleChip(text = title(option), selected = option == selected) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF7C4DFF),
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
private fun AnimatedBackdrop(backdrop: Backdrop, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
    )
    Canvas(modifier = modifier) {
        when (backdrop) {
            Backdrop.None -> Unit // Nothing behind the video but the screen itself.
            Backdrop.Checkerboard -> drawCheckerboard(shift = progress)
            Backdrop.Gradient -> drawSweepingGradient(progress = progress)
            Backdrop.Stripes -> drawStripes(shift = progress)
        }
    }
}

private fun DrawScope.drawCheckerboard(shift: Float) {
    val cell = 24.dp.toPx()
    val offset = shift * cell * 2
    val light = Color(0xFF3A3450)
    val dark = Color(0xFF241F33)
    drawRect(dark)
    val columns = (size.width / cell).toInt() + 3
    val rows = (size.height / cell).toInt() + 3
    for (row in -2 until rows) {
        for (column in -2 until columns) {
            if ((row + column) % 2 != 0) continue
            drawRect(
                color = light,
                topLeft = Offset(column * cell + offset, row * cell + offset),
                size = Size(cell, cell),
            )
        }
    }
}

private fun DrawScope.drawSweepingGradient(progress: Float) {
    rotate(degrees = progress * 360f) {
        drawRect(
            brush = Brush.sweepGradient(
                listOf(Color(0xFFFF6B6B), Color(0xFFFFD93D), Color(0xFF6BCB77), Color(0xFF4D96FF), Color(0xFFFF6B6B)),
            ),
            topLeft = Offset(-size.width, -size.height),
            size = Size(size.width * 3, size.height * 3),
        )
    }
}

private fun DrawScope.drawStripes(shift: Float) {
    val stripe = 28.dp.toPx()
    drawRect(Color(0xFF101820))
    rotate(degrees = 45f) {
        val diagonal = size.width + size.height
        var x = -diagonal + shift * stripe * 2
        while (x < diagonal) {
            drawRect(
                color = Color(0xFFF2AA4C),
                topLeft = Offset(x, -diagonal),
                size = Size(stripe, diagonal * 3),
            )
            x += stripe * 2
        }
    }
}
