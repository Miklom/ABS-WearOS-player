package org.wearabs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun BookScreen(
    viewModel: BookViewModel,
    onPlay: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val book = state.book
    val download = state.download

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    text = book?.title ?: "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Text(
                    text = formatDuration(book?.duration ?: 0.0),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }

            when {
                download != null -> item {
                    DownloadProgress(download.percent, download.slow)
                }

                book?.downloaded == true -> {
                    item {
                        Button(
                            onClick = onPlay,
                            label = { Text("Play") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        FilledTonalButton(
                            onClick = viewModel::delete,
                            label = { Text("Delete") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                state.tracks.isNotEmpty() -> item {
                    Button(
                        onClick = viewModel::download,
                        label = { Text("Download") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                state.refreshing -> item { CenteredSpinner() }

                else -> item {
                    CenteredMessage(state.error ?: "No audio files")
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(percent: Int, slow: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
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
