package org.wearabs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Playback over the cover art. Two rows of controls rather than one: five
 * buttons side by side do not survive the corners of a round display.
 *
 * The rim arc is progress through the whole book; the text shows position
 * inside the current chapter above position inside the book.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fraction = if (state.duration > 0) {
        (state.position / state.duration).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    CoverBackdrop(model = state.cover) {
        ScreenScaffold {
            CircularProgressIndicator(
                progress = { fraction },
                startAngle = 292.5f,
                endAngle = 247.5f,
                strokeWidth = 4.dp,
                modifier = Modifier.fillMaxSize().padding(3.dp)
            )

            val error = state.error
            if (error != null) {
                CenteredColumn { CenteredMessage(error) }
                return@ScreenScaffold
            }

            CenteredColumn {
                Text(
                    text = state.chapterTitle ?: state.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))

                if (state.hasChapters) {
                    Text(
                        text = "${formatDuration(state.chapterPosition)} / " +
                            formatDuration(state.chapterDuration),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = "${formatDuration(state.position)} / ${formatDuration(state.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.seekBy(-SKIP_SECONDS) },
                        enabled = state.ready,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Back10Icon, "Back 10 seconds", Modifier.size(22.dp))
                    }

                    FilledIconButton(
                        onClick = viewModel::togglePlayPause,
                        enabled = state.ready,
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = if (state.playing) PauseIcon else PlayIcon,
                            contentDescription = if (state.playing) "Pause" else "Play",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.seekBy(SKIP_SECONDS) },
                        enabled = state.ready,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Forward10Icon, "Forward 10 seconds", Modifier.size(22.dp))
                    }
                }

                // Only for books that actually carry chapter marks.
                if (state.hasChapters) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = viewModel::previousChapter,
                            enabled = state.ready && state.canPreviousChapter,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(PreviousIcon, "Previous chapter", Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = viewModel::nextChapter,
                            enabled = state.ready && state.canNextChapter,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(NextIcon, "Next chapter", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

private const val SKIP_SECONDS = 10.0

/**
 * The Material icon set is a large dependency for a handful of glyphs, so the
 * transport controls are drawn here. Icon() tints them, so the fill colour is
 * just a placeholder.
 */
private fun glyph(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        paths.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.White)
            )
        }
    }.build()

private val PlayIcon: ImageVector by lazy { glyph("Play", "M8 5v14l11 -7z") }

private val PauseIcon: ImageVector by lazy { glyph("Pause", "M6 5h4v14H6zM14 5h4v14h-4z") }

private val PreviousIcon: ImageVector by lazy {
    glyph("PreviousChapter", "M6 6h2v12H6z", "M18 6v12l-9 -6z")
}

private val NextIcon: ImageVector by lazy {
    glyph("NextChapter", "M16 6h2v12h-2z", "M6 6v12l9 -6z")
}

/** Circular arrow with a "10" inside, the usual skip-back affordance. */
private val Back10Icon: ImageVector by lazy {
    glyph(
        "Back10",
        "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
        "M9.3 16.2v-3.5h-.7v-.7h1.5v4.2zM13.6 12c.8 0 1.3.6 1.3 1.6v1.1c0 1-.5 1.6-1.3 1.6s-1.3-.6-1.3-1.6v-1.1c0-1 .5-1.6 1.3-1.6zm0 .7c-.3 0-.5.3-.5.9v1.1c0 .6.2.9.5.9s.5-.3.5-.9v-1.1c0-.6-.2-.9-.5-.9z"
    )
}

private val Forward10Icon: ImageVector by lazy {
    glyph(
        "Forward10",
        "M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z",
        "M9.3 16.2v-3.5h-.7v-.7h1.5v4.2zM13.6 12c.8 0 1.3.6 1.3 1.6v1.1c0 1-.5 1.6-1.3 1.6s-1.3-.6-1.3-1.6v-1.1c0-1 .5-1.6 1.3-1.6zm0 .7c-.3 0-.5.3-.5.9v1.1c0 .6.2.9.5.9s.5-.3.5-.9v-1.1c0-.6-.2-.9-.5-.9z"
    )
}
