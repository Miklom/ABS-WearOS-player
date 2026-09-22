package org.wearabs.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book the user has opened or downloaded on the watch. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val itemId: String,
    val title: String,
    val author: String,
    /** Total length in seconds. */
    val duration: Double,
    /** True only once every track file is fully downloaded. */
    val downloaded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/** One audio file of a book, in playback order. */
@Entity(
    tableName = "tracks",
    primaryKeys = ["itemId", "trackIndex"],
    indices = [Index("itemId")]
)
data class TrackEntity(
    val itemId: String,
    /** Index within the book's track list, 0-based and contiguous. */
    val trackIndex: Int,
    /** Inode, used as the file id in the download URL. */
    val ino: String,
    /** Seconds from the start of the book at which this track begins. */
    val startOffset: Double,
    val duration: Double,
    val mimeType: String?,
    val fileName: String,
    /** Size reported by the server, or 0 when unknown. */
    val fileSize: Long,
    /** Absolute path once the file is fully downloaded, otherwise null. */
    val localPath: String? = null
)

/** One chapter mark of a book, in seconds from the start of the whole book. */
@Entity(
    tableName = "chapters",
    primaryKeys = ["itemId", "chapterIndex"],
    indices = [Index("itemId")]
)
data class ChapterEntity(
    val itemId: String,
    /** Position in the chapter list, 0-based and contiguous. */
    val chapterIndex: Int,
    val start: Double,
    val end: Double,
    val title: String
)

/** Listening position, kept locally first and pushed to the server by SyncWorker. */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val itemId: String,
    /** Position in seconds from the start of the whole book. */
    val currentTime: Double,
    val duration: Double,
    /** Epoch millis of the last local change. */
    val lastUpdate: Long,
    val isFinished: Boolean = false,
    val synced: Boolean = false
)

/** Fraction listened, 0..1 — what the server stores alongside currentTime. */
val ProgressEntity.fraction: Double
    get() = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0
