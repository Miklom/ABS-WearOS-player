package org.wearabs.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import org.wearabs.data.BookEntity
import org.wearabs.data.TrackEntity

/** Where in the playlist a global book position falls. */
data class SeekTarget(val trackIndex: Int, val positionMs: Long)

/**
 * Builds the ExoPlayer playlist for a book. Works for a single m4b as well as a
 * multi-file mp3 book: each track becomes one MediaItem, and the offsets needed
 * to translate between global book time and per-track time ride along in extras.
 */
fun buildPlaylist(book: BookEntity, tracks: List<TrackEntity>): List<MediaItem> =
    tracks.mapNotNull { track ->
        val path = track.localPath ?: return@mapNotNull null
        val uri: Uri = File(path).toUri()
        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, book.itemId)
            putDouble(EXTRA_START_OFFSET, track.startOffset)
            putDouble(EXTRA_BOOK_DURATION, bookDuration(book, tracks))
        }
        MediaItem.Builder()
            .setMediaId("${book.itemId}:${track.trackIndex}")
            .setUri(uri)
            // The session strips localConfiguration, so keep the URI here too.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMimeType(track.mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

/** Server duration when known, otherwise the sum of the track durations. */
fun bookDuration(book: BookEntity, tracks: List<TrackEntity>): Double =
    if (book.duration > 0) book.duration else tracks.sumOf { it.duration }

/** Global book position in seconds -> (track index, offset inside that track). */
fun seekTargetFor(tracks: List<TrackEntity>, globalSeconds: Double): SeekTarget {
    if (tracks.isEmpty()) return SeekTarget(0, 0L)
    val clamped = globalSeconds.coerceAtLeast(0.0)
    val index = tracks.indexOfLast { it.startOffset <= clamped }.coerceAtLeast(0)
    val track = tracks[index]
    val within = (clamped - track.startOffset).coerceIn(0.0, maxOf(track.duration, 0.0))
    return SeekTarget(index, (within * 1000).toLong())
}

/** (track index, offset inside that track) -> global book position in seconds. */
fun globalPosition(tracks: List<TrackEntity>, trackIndex: Int, positionMs: Long): Double {
    val offset = tracks.getOrNull(trackIndex)?.startOffset ?: 0.0
    return offset + positionMs / 1000.0
}
