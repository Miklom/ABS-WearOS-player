package org.wearabs.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wearabs.WearAbsApp
import org.wearabs.data.BookEntity
import org.wearabs.data.ProgressEntity
import org.wearabs.data.TrackEntity
import org.wearabs.net.Covers
import org.wearabs.work.DownloadState
import org.wearabs.work.DownloadWorker

data class BookUiState(
    val book: BookEntity? = null,
    /** File or URL for Coil; null when the book has no cover. */
    val cover: Any? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val download: DownloadState? = null,
    /** Listening position, for the progress bar. */
    val progress: ProgressEntity? = null,
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
        repository.observeProgress(itemId),
        transient
    ) { book, tracks, download, progress, extra ->
        extra.copy(book = book, tracks = tracks, download = download, progress = progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    init {
        refresh()
        viewModelScope.launch {
            val cover = withContext(Dispatchers.IO) {
                repository.coverModel(itemId, Covers.LARGE)
            }
            transient.value = transient.value.copy(cover = cover)
        }
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
            // A finished download writes cover.jpg, so re-resolve afterwards.
            val cover = withContext(Dispatchers.IO) { repository.coverModel(itemId, Covers.LARGE) }
            transient.value = transient.value.copy(cover = cover)
        }
    }

    fun download() = DownloadWorker.enqueue(getApplication(), itemId)

    fun delete() {
        DownloadWorker.cancel(getApplication(), itemId)
        viewModelScope.launch { repository.deleteDownload(itemId) }
    }
}
