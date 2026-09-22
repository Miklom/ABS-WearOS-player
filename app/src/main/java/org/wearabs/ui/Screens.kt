package org.wearabs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

// ---- Home ------------------------------------------------------------------

/**
 * The library as a grid of cover art. Two tiles per row is what fits on a round
 * watch without the artwork becoming unreadable, and Search sits in an
 * EdgeButton hugging the bottom rim.
 */
@Composable
fun HomeScreen(
    tiles: List<LibraryTile>,
    onSearch: () -> Unit,
    onBook: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSearch, buttonSize = EdgeButtonSize.Medium) {
                Text("Search")
            }
        }
    ) { contentPadding ->
        val rows = tiles.chunked(2)

        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec)
                ) { Text("Library") }
            }

            if (tiles.isEmpty()) {
                item {
                    CenteredMessage("Nothing downloaded yet")
                }
            }

            // Two per row; a trailing odd tile keeps its size rather than stretching.
            items(rows.size, key = { rows[it].first().itemId }) { rowIndex ->
                val row = rows[rowIndex]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .transformedHeight(this, spec)
                ) {
                    row.forEach { tile ->
                        BookCover(
                            model = tile.cover,
                            title = tile.title,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                // The cover is the tap target itself.
                                .clickable { onBook(tile.itemId) }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                FilledTonalButton(
                    onClick = onSignOut,
                    label = { Text("Sign out") },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .transformedHeight(this, spec)
                )
            }
        }
    }
}

// ---- Search ----------------------------------------------------------------

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onLaunchInput: () -> Unit,
    onBook: (org.wearabs.data.BookEntity) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    // Opening Search goes straight to the keyboard; nothing to show before that.
    LaunchedEffect(Unit) {
        if (!state.searched && state.query.isBlank()) onLaunchInput()
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onLaunchInput, buttonSize = EdgeButtonSize.Small) {
                Text(state.query.ifBlank { "Search" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.loading -> item { CenteredSpinner() }
                state.error != null -> item { CenteredMessage(state.error!!) }
                state.results.isEmpty() && state.searched -> item { CenteredMessage("No results") }
                else -> items(
                    state.results.size,
                    key = { state.results[it].book.itemId }
                ) { index ->
                    val hit = state.results[index]
                    Button(
                        onClick = { onBook(hit.book) },
                        label = { Text(hit.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = if (hit.book.author.isNotBlank()) {
                            { Text(hit.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            null
                        },
                        icon = {
                            BookCover(
                                model = hit.cover,
                                title = hit.book.title,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(32.dp)
                            )
                        },
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                    )
                }
            }
        }
    }
}

// ---- Shared bits -----------------------------------------------------------

@Composable
fun CenteredSpinner() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
fun CenteredMessage(message: String) {
    Text(
        text = message,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}
