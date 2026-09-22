package org.wearabs.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.wearabs.WearAbsApp
import org.wearabs.data.BookEntity
import org.wearabs.data.TrackEntity
import org.wearabs.work.DownloadState
import org.wearabs.work.DownloadWorker

data class BookUiState(
    val book: BookEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val download: DownloadState? = null,
    val refreshing: Boolean = false,
    val error: String? = null
)

class BookViewModel(application: Application, private val itemId: String) :
    AndroidViewModel(application) {

    private val repository = WearAbsApp.container().repository
    private val transient = MutableStateFlow(BookUiState())

    val state: StateFlow<BookUiState> = combine(
        repository.observeBook(itemId),
        repository.observeTracks(itemId),
        DownloadWorker.observe(application, itemId),
        transient
    ) { book, tracks, download, extra ->
        extra.copy(book = book, tracks = tracks, download = download)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    init {
        refresh()
    }

    /** Pulls the item detail so duration and the track list are up to date. */
    fun refresh() {
        viewModelScope.launch {
            transient.value = transient.value.copy(refreshing = true, error = null)
            try {
                repository.refreshBook(itemId)
                transient.value = transient.value.copy(refreshing = false)
            } catch (e: Exception) {
                // Offline is fine — whatever is in Room is still shown.
                transient.value = transient.value.copy(refreshing = false, error = e.message)
            }
        }
    }

    fun download() = DownloadWorker.enqueue(getApplication(), itemId)

    fun delete() {
        DownloadWorker.cancel(getApplication(), itemId)
        viewModelScope.launch { repository.deleteDownload(itemId) }
    }
}
