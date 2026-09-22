package org.wearabs.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wearabs.WearAbsApp
import org.wearabs.data.TrackEntity
import org.wearabs.net.Covers
import org.wearabs.player.PlayerService
import org.wearabs.player.bookDuration
import org.wearabs.player.buildPlaylist
import org.wearabs.player.globalPosition
import org.wearabs.player.seekTargetFor

data class PlayerUiState(
    val title: String = "",
    val author: String = "",
    /** Global position in seconds from the start of the book. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val playing: Boolean = false,
    /** File or URL for Coil; null when the book has no cover. */
    val cover: Any? = null,
    val ready: Boolean = false,
    val error: String? = null
)

class PlayerViewModel(application: Application, private val itemId: String) :
    AndroidViewModel(application) {

    private val repository = WearAbsApp.container().repository

    private var controller: MediaController? = null
    private var tracks: List<TrackEntity> = emptyList()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
            if (isPlaying) startTicker() else stopTicker()
            refreshPosition()
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) = refreshPosition()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) = refreshPosition()
    }

    init {
        viewModelScope.launch { connect() }
    }

    private suspend fun connect() {
        val application = getApplication<Application>()
        val book = repository.book(itemId)
        tracks = repository.tracks(itemId).filter { it.localPath != null }

        if (book == null || tracks.isEmpty()) {
            _state.value = _state.value.copy(error = "Book is not downloaded")
            return
        }

        val duration = bookDuration(book, tracks)
        _state.value = _state.value.copy(
            title = book.title,
            author = book.author,
            duration = duration,
            cover = withContext(Dispatchers.IO) { repository.coverModel(itemId, Covers.LARGE) }
        )

        // Resume where we left off; the server wins if it has something newer.
        val startPosition = repository.resolveStartPosition(itemId, duration)

        val token = SessionToken(application, ComponentName(application, PlayerService::class.java))
        val mediaController = MediaController.Builder(application, token).buildAsync().await()
        controller = mediaController
        mediaController.addListener(listener)

        val alreadyLoaded = mediaController.currentMediaItem
            ?.mediaId
            ?.substringBefore(':') == itemId

        if (!alreadyLoaded) {
            val target = seekTargetFor(tracks, startPosition)
            mediaController.setMediaItems(
                buildPlaylist(book, tracks),
                target.trackIndex,
                target.positionMs
            )
            mediaController.prepare()
        }

        _state.value = _state.value.copy(
            ready = true,
            playing = mediaController.isPlaying
        )
        refreshPosition()
        if (mediaController.isPlaying) startTicker()
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (true) {
                refreshPosition()
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun refreshPosition() {
        val controller = controller ?: return
        val position = globalPosition(
            tracks,
            controller.currentMediaItemIndex,
            controller.currentPosition
        )
        _state.value = _state.value.copy(position = position)
    }

    override fun onCleared() {
        stopTicker()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
