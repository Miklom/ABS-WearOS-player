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
import org.wearabs.data.ChapterEntity
import org.wearabs.data.TrackEntity
import org.wearabs.net.Covers
import org.wearabs.player.PlayerService
import org.wearabs.player.bookDuration
import org.wearabs.player.chapterIndexAt
import org.wearabs.player.nextChapterTarget
import org.wearabs.player.previousChapterTarget
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
    val error: String? = null,
    // ---- Chapters, absent for books that have none ----
    val chapterTitle: String? = null,
    /** Seconds into the current chapter. */
    val chapterPosition: Double = 0.0,
    val chapterDuration: Double = 0.0,
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false
) {
    val hasChapters: Boolean get() = chapterTitle != null
}

class PlayerViewModel(application: Application, private val itemId: String) :
    AndroidViewModel(application) {

    private val repository = WearAbsApp.container().repository

    private var controller: MediaController? = null
    private var tracks: List<TrackEntity> = emptyList()
    private var chapters: List<ChapterEntity> = emptyList()
    private var ticker: Job? = null

    /**
     * Whether the player screen is actually on screen. During a multi-hour
     * listen the wrist is down and the display is off almost all of the time;
     * without this the ticker would keep waking up to update a UI nobody is
     * looking at.
     */
    private var uiVisible = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
            updateTicker()
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
        chapters = repository.chapters(itemId)

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

        _state.value = _state.value.copy(ready = true, playing = mediaController.isPlaying)
        refreshPosition()
        updateTicker()
    }

    /** Called by the screen as it enters and leaves the resumed state. */
    fun setUiVisible(visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        if (visible) refreshPosition()
        updateTicker()
    }

    // ---- Controls ----------------------------------------------------------

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    /** Jump by a fixed number of seconds, clamped to the book. */
    fun seekBy(deltaSeconds: Double) {
        seekToGlobal(_state.value.position + deltaSeconds)
    }

    /**
     * Back to the start of the current chapter, or to the previous one when
     * already near the start — the convention every audio player uses.
     */
    fun previousChapter() {
        seekToGlobal(previousChapterTarget(chapters, _state.value.position) ?: return)
    }

    fun nextChapter() {
        seekToGlobal(nextChapterTarget(chapters, _state.value.position) ?: return)
    }

    private fun seekToGlobal(seconds: Double) {
        val controller = controller ?: return
        if (tracks.isEmpty()) return
        val clamped = seconds.coerceIn(0.0, _state.value.duration.coerceAtLeast(0.0))
        val target = seekTargetFor(tracks, clamped)
        controller.seekTo(target.trackIndex, target.positionMs)
        refreshPosition()
    }

    // ---- Position ----------------------------------------------------------

    /** The ticker is only worth running while something is playing and visible. */
    private fun updateTicker() {
        val shouldRun = uiVisible && _state.value.playing
        if (shouldRun) {
            if (ticker?.isActive == true) return
            ticker = viewModelScope.launch {
                while (true) {
                    refreshPosition()
                    // The readout is in whole seconds, so twice a second was
                    // twice the wake-ups for no visible difference.
                    delay(POSITION_REFRESH_MS)
                }
            }
        } else {
            ticker?.cancel()
            ticker = null
        }
    }

    private fun refreshPosition() {
        val controller = controller ?: return
        val position = globalPosition(
            tracks,
            controller.currentMediaItemIndex,
            controller.currentPosition
        )

        val index = chapterIndexAt(chapters, position)
        val chapter = index?.let { chapters[it] }

        _state.value = _state.value.copy(
            position = position,
            chapterTitle = chapter?.title,
            chapterPosition = chapter?.let { (position - it.start).coerceAtLeast(0.0) } ?: 0.0,
            chapterDuration = chapter?.let { (it.end - it.start).coerceAtLeast(0.0) } ?: 0.0,
            canPreviousChapter = chapter != null,
            canNextChapter = index != null && index < chapters.lastIndex
        )
    }

    override fun onCleared() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private companion object {
        const val POSITION_REFRESH_MS = 1_000L
    }
}
