package org.wearabs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.wearabs.WearAbsApp
import org.wearabs.data.BookEntity
import org.wearabs.net.Covers
import org.wearabs.net.SessionExpiredException

/** A search hit plus the cover model Coil should load for it. */
data class SearchHit(val book: BookEntity, val cover: Any?)

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SearchHit> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false
)

class SearchViewModel : ViewModel() {

    private val repository = WearAbsApp.container().repository

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _state.value = SearchUiState(query = trimmed, loading = true)
        viewModelScope.launch {
            try {
                val results = repository.search(trimmed).map { book ->
                    SearchHit(book, repository.coverModel(book.itemId, Covers.THUMB))
                }
                _state.value = SearchUiState(
                    query = trimmed,
                    results = results,
                    searched = true
                )
            } catch (e: SessionExpiredException) {
                // AuthStore has been cleared, so the nav host drops to Login.
                _state.value = SearchUiState(query = trimmed, error = "Signed out", searched = true)
            } catch (e: Exception) {
                _state.value = SearchUiState(
                    query = trimmed,
                    error = e.message ?: "Search failed",
                    searched = true
                )
            }
        }
    }

    /** Caches the picked result so the Book screen can render without a round trip. */
    fun remember(book: BookEntity) {
        viewModelScope.launch { repository.rememberBook(book) }
    }
}
