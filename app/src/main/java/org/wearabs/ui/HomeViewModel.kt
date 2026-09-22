package org.wearabs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.wearabs.WearAbsApp
import org.wearabs.net.Covers

/** One tile in the library grid. */
data class LibraryTile(
    val itemId: String,
    val title: String,
    /** A File or a URL for Coil; null when the book has no cover at all. */
    val cover: Any?
)

class HomeViewModel : ViewModel() {

    private val container = WearAbsApp.container()
    private val repository = container.repository

    /**
     * Downloaded books only, so the library works with no network at all.
     * Cover lookup touches the filesystem, so it is resolved here rather than
     * during composition.
     */
    val tiles: StateFlow<List<LibraryTile>> = repository.downloadedBooks()
        .map { books ->
            books.map { book ->
                LibraryTile(
                    itemId = book.itemId,
                    title = book.title,
                    cover = repository.coverModel(book.itemId, Covers.TILE)
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Forgets the session. Downloaded files and positions are left alone, so
     * signing back in to the same server picks them straight back up.
     */
    fun signOut() = container.api.logout()
}
