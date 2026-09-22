package org.wearabs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * Book detail over its own cover art. The primary action — download, or play
 * once downloaded — lives in the EdgeButton at the bottom rim.
 */
@Composable
fun BookScreen(
    viewModel: BookViewModel,
    onPlay: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val book = state.book
    val download = state.download
    val downloaded = book?.downloaded == true

    CoverBackdrop(model = state.cover) {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                when {
                    download != null -> Unit
                    downloaded -> EdgeButton(onClick = onPlay, buttonSize = EdgeButtonSize.Large) {
                        Text("Play")
                    }
                    state.tracks.isNotEmpty() -> EdgeButton(
                        onClick = viewModel::download,
                        buttonSize = EdgeButtonSize.Medium
                    ) { Text("Download") }
                    else -> Unit
                }
            }
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    BookCover(
                        model = state.cover,
                        title = book?.title.orEmpty(),
                        modifier = Modifier
                            .size(76.dp)
                            .transformedHeight(this, spec)
                    )
                }
                item {
                    Text(
                        text = book?.title ?: "Loading…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .transformedHeight(this, spec)
                    )
                }
                if (!book?.author.isNullOrBlank()) {
                    item {
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                        )
                    }
                }
                item {
                    Text(
                        text = formatDuration(book?.duration ?: 0.0),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .transformedHeight(this, spec)
                    )
                }

                when {
                    download != null -> item {
                        DownloadProgress(download.percent, download.slow)
                    }

                    downloaded -> item {
                        FilledTonalButton(
                            onClick = viewModel::delete,
                            label = { Text("Delete") },
                            transformation = SurfaceTransformation(spec),
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                        )
                    }

                    state.tracks.isEmpty() && state.refreshing -> item { CenteredSpinner() }

                    state.tracks.isEmpty() -> item {
                        CenteredMessage(state.error ?: "No audio files")
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(percent: Int, slow: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        CircularProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Downloading $percent%",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        if (slow) {
            Text(
                text = "No Wi-Fi — this will be slow",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
