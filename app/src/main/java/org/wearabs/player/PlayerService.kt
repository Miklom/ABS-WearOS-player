package org.wearabs.player

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wearabs.WearAbsApp
import org.wearabs.work.SyncWorker

/**
 * Owns the ExoPlayer instance and the MediaSession, so playback survives the
 * Activity going away and the system media controls and headset buttons work.
 *
 * It is also where listening progress is recorded: every 10 seconds while
 * playing, and immediately on pause or stop.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Everything is played from local files, so a partial wake lock is
            // all that is needed — but it is needed: a dozing watch will
            // otherwise stall playback once the screen has been off a while.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply { addListener(PlaybackListener()) }

        session = MediaSession.Builder(this, player)
            .setCallback(RestoreUriCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveProgress()
        ticker?.cancel()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---- Progress recording ------------------------------------------------

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                saveProgress()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun saveProgress(alsoSync: Boolean = false) {
        val item = player.currentMediaItem ?: return
        val bookId = item.bookId ?: return
        val position = item.startOffsetSeconds + player.currentPosition / 1000.0
        val duration = item.bookDurationSeconds

        val container = WearAbsApp.container()
        val context = applicationContext
        // Deliberately not this service's scope: onDestroy saves and then the
        // scope is cancelled, which would drop the write.
        container.appScope.launch {
            container.repository.saveLocalProgress(bookId, position, duration)
            if (alsoSync) SyncWorker.enqueueNow(context)
        }
    }

    private inner class PlaybackListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicker()
            } else {
                stopTicker()
                // A pause is a good moment to get the position onto the server.
                saveProgress(alsoSync = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                stopTicker()
                saveProgress(alsoSync = true)
            }
        }
    }

    /**
     * MediaItems lose their local URI when they cross the controller/session
     * boundary, so rebuild each one from `requestMetadata.mediaUri`.
     */
    private inner class RestoreUriCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val restored = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(restored)
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
    }
}

// ---- Extras carried on each MediaItem --------------------------------------

const val EXTRA_BOOK_ID = "bookId"
const val EXTRA_START_OFFSET = "startOffset"
const val EXTRA_BOOK_DURATION = "bookDuration"

val MediaItem.extrasBundle: Bundle?
    get() = mediaMetadata.extras

val MediaItem.bookId: String?
    get() = extrasBundle?.getString(EXTRA_BOOK_ID)

/** Seconds from the start of the book at which this track begins. */
val MediaItem.startOffsetSeconds: Double
    get() = extrasBundle?.getDouble(EXTRA_START_OFFSET) ?: 0.0

val MediaItem.bookDurationSeconds: Double
    get() = extrasBundle?.getDouble(EXTRA_BOOK_DURATION) ?: 0.0
