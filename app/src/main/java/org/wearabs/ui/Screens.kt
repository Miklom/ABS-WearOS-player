package org.wearabs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import org.wearabs.data.BookEntity
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
 * The library as a plain vertical text list — faster to scan on a watch than a
 * wall of thumbnails, where the artwork is too small to tell books apart.
 *
 * Search and Sign out are both list items rather than an EdgeButton, because an
 * EdgeButton always sits at the bottom rim and Search belongs above Sign out.
 */
@Composable
fun HomeScreen(
    books: List<BookEntity>,
    onSearch: () -> Unit,
    onBook: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var confirmSignOut by remember { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
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

            if (books.isEmpty()) {
                item { CenteredMessage("Nothing downloaded yet") }
            }

            items(books.size, key = { books[it].itemId }) { index ->
                val book = books[index]
                FilledTonalButton(
                    onClick = { onBook(book.itemId) },
                    label = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (book.author.isNotBlank()) {
                        { Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                )
            }

            item {
                Button(
                    onClick = onSearch,
                    label = { Text("Search") },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .transformedHeight(this, spec)
                )
            }
            item {
                FilledTonalButton(
                    onClick = { confirmSignOut = true },
                    label = { Text("Sign out") },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                )
            }
        }
    }

    ConfirmDialog(
        visible = confirmSignOut,
        title = "Sign out?",
        detail = "Downloads and positions are kept.",
        onConfirm = {
            confirmSignOut = false
            onSignOut()
        },
        onDismiss = { confirmSignOut = false }
    )
}

// ---- Search ----------------------------------------------------------------

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onLaunchInput: () -> Unit,
    onBook: (BookEntity) -> Unit
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
                        onClick = {
                            // Cache it first, so the Book screen has title,
                            // author and duration even before the item fetch
                            // returns — and at all when offline.
                            viewModel.remember(hit.book)
                            onBook(hit.book)
                        },
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
