package org.wearabs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.wearabs.data.BookEntity

// ---- Home ------------------------------------------------------------------

@Composable
fun HomeScreen(
    books: List<BookEntity>,
    onSearch: () -> Unit,
    onBook: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("Library") } }
            item {
                Button(
                    onClick = onSearch,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (books.isEmpty()) {
                item {
                    Text(
                        text = "No downloaded books yet",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
            items(books, key = { it.itemId }) { book ->
                BookRow(book) { onBook(book.itemId) }
            }
            item {
                FilledTonalButton(
                    onClick = onSignOut,
                    label = { Text("Sign out") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        label = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (book.author.isNotBlank()) {
            { Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
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
    val listState = rememberScalingLazyListState()

    // Opening Search goes straight to the keyboard; nothing to show before that.
    LaunchedEffect(Unit) {
        if (!state.searched && state.query.isBlank()) onLaunchInput()
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Button(
                    onClick = onLaunchInput,
                    label = {
                        Text(state.query.ifBlank { "Search books" }, maxLines = 1)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when {
                state.loading -> item { CenteredSpinner() }
                state.error != null -> item { CenteredMessage(state.error!!) }
                state.results.isEmpty() && state.searched ->
                    item { CenteredMessage("No results") }
                else -> items(state.results, key = { it.itemId }) { book ->
                    BookRow(book) {
                        viewModel.remember(book)
                        onBook(book)
                    }
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
