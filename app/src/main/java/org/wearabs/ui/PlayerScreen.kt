package org.wearabs.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** Title, position / total, and one big play-pause button. Nothing else. */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold {
        CenteredColumn {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${formatDuration(state.position)} / ${formatDuration(state.duration)}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            val error = state.error
            if (error != null) {
                CenteredMessage(error)
            } else {
                IconButton(
                    onClick = viewModel::togglePlayPause,
                    enabled = state.ready,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (state.playing) PauseIcon else PlayIcon,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

/**
 * The Material icon set is a large dependency for two glyphs, so play and pause
 * are drawn here. Icon() tints them, so the fill colour is just a placeholder.
 */
private fun glyph(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.White)
        )
    }.build()

private val PlayIcon: ImageVector by lazy { glyph("Play", "M8 5v14l11 -7z") }

private val PauseIcon: ImageVector by lazy { glyph("Pause", "M6 5h4v14H6zM14 5h4v14h-4z") }
