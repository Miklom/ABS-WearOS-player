package org.wearabs.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.wearabs.net.AbsApi
import org.wearabs.net.Covers
import org.wearabs.net.LibraryItemDto
import org.wearabs.net.ProgressUpdateDto

/**
 * Single place where the API, the Room database and the downloaded files meet.
 * Everything the UI and the workers need goes through here.
 */
class Repository(
    private val context: Context,
    val api: AbsApi,
    private val authStore: AuthStore,
    val db: AppDatabase
) {

    // ---- Library -----------------------------------------------------------

    /**
     * Library to search. Login usually supplies one as `userDefaultLibraryId`;
     * otherwise the first library is fetched once and cached on the session.
     */
    suspend fun libraryId(): String? {
        authStore.current?.defaultLibraryId?.let { return it }
        val id = api.firstLibraryId() ?: return null
        authStore.updateLibraryId(id)
        return id
    }

    suspend fun search(query: String): List<BookEntity> {
        val libraryId = libraryId() ?: return emptyList()
        return api.search(libraryId, query, limit = SEARCH_LIMIT).map { it.toBookEntity() }
    }

    // ---- Books -------------------------------------------------------------

    fun downloadedBooks(): Flow<List<BookEntity>> = db.books().observeDownloaded()

    fun observeBook(itemId: String): Flow<BookEntity?> = db.books().observe(itemId)

    fun observeTracks(itemId: String): Flow<List<TrackEntity>> = db.tracks().observeForBook(itemId)

    fun observeProgress(itemId: String): Flow<ProgressEntity?> = db.progress().observe(itemId)

    suspend fun book(itemId: String): BookEntity? = db.books().find(itemId)

    suspend fun tracks(itemId: String): List<TrackEntity> = db.tracks().forBook(itemId)

    suspend fun chapters(itemId: String): List<ChapterEntity> = db.chapters().forBook(itemId)

    /**
     * Fetches the item from the server and stores the book plus its track list.
     * Already-downloaded files are kept when the track list is unchanged.
     */
    suspend fun refreshBook(itemId: String): BookEntity {
        val item = api.item(itemId)
        val book = item.toBookEntity()
        val existing = db.books().find(itemId)
        db.books().upsert(book.copy(downloaded = existing?.downloaded ?: false))
        db.tracks().replaceKeepingLocalFiles(itemId, item.toTrackEntities())
        db.chapters().replace(itemId, item.toChapterEntities())
        reconcileDownloadState(itemId)
        return db.books().find(itemId) ?: book
    }

    /** Stores a search result so the Book screen can render it offline too. */
    suspend fun rememberBook(book: BookEntity) {
        val existing = db.books().find(book.itemId)
        db.books().upsert(book.copy(downloaded = existing?.downloaded ?: false))
    }

    // ---- Files -------------------------------------------------------------

    /** App-internal directory holding a book's audio files: files/books/{itemId}/ */
    fun bookDir(itemId: String): File = File(File(context.filesDir, "books"), itemId)

    fun trackFile(track: TrackEntity): File =
        File(bookDir(track.itemId), "%03d-%s".format(track.trackIndex, track.fileName))

    fun partFile(track: TrackEntity): File = File(trackFile(track).path + ".part")

    /** Cover saved beside the audio files when the book was downloaded. */
    fun coverFile(itemId: String): File = File(bookDir(itemId), "cover.jpg")

    /**
     * What Coil should load for a book: the downloaded file when there is one,
     * otherwise the server URL. Null when neither is available.
     */
    fun coverModel(itemId: String, width: Int): Any? {
        val local = coverFile(itemId)
        if (local.exists() && local.length() > 0) return local
        val serverUrl = authStore.current?.serverUrl ?: return null
        return Covers.url(serverUrl, itemId, width)
    }

    /**
     * Re-derives the book's `downloaded` flag from what is actually on disk, so
     * a half-finished or externally removed download can never look complete.
     */
    suspend fun reconcileDownloadState(itemId: String): Boolean {
        val tracks = db.tracks().forBook(itemId)
        val complete = tracks.isNotEmpty() && tracks.all { track ->
            val file = trackFile(track)
            track.localPath == file.path && file.exists() && file.length() > 0
        }
        db.books().setDownloaded(itemId, complete)
        return complete
    }

    /**
     * Drops every downloaded file and every cached row. Used when signing in to
     * a different account, whose item ids mean nothing to this library.
     */
    suspend fun clearLocalLibrary() = withContext(Dispatchers.IO) {
        File(context.filesDir, "books").deleteRecursively()
        db.clearAllTables()
    }

    suspend fun deleteDownload(itemId: String) {
        bookDir(itemId).deleteRecursively()
        db.tracks().forBook(itemId).forEach { db.tracks().setLocalPath(itemId, it.trackIndex, null) }
        db.books().setDownloaded(itemId, false)
    }

    // ---- Progress ----------------------------------------------------------

    /** Records a new local position. Always marked unsynced for SyncWorker to pick up. */
    suspend fun saveLocalProgress(itemId: String, currentTime: Double, duration: Double) {
        val finished = duration > 0 && currentTime >= duration - FINISHED_SLACK_SECONDS
        db.progress().upsert(
            ProgressEntity(
                itemId = itemId,
                currentTime = currentTime.coerceAtLeast(0.0),
                duration = duration,
                lastUpdate = System.currentTimeMillis(),
                isFinished = finished,
                synced = false
            )
        )
    }

    suspend fun localProgress(itemId: String): ProgressEntity? = db.progress().find(itemId)

    /**
     * Position to resume a book at. When the server is reachable its progress is
     * fetched first and whichever side has the newer lastUpdate wins.
     */
    suspend fun resolveStartPosition(itemId: String, duration: Double): Double {
        val local = db.progress().find(itemId)
        val remote = runCatching { api.progress(itemId) }
            .onFailure { Log.d(TAG, "No server progress for $itemId: ${it.message}") }
            .getOrNull()

        if (remote != null && (local == null || remote.lastUpdate > local.lastUpdate)) {
            db.progress().upsert(
                ProgressEntity(
                    itemId = itemId,
                    currentTime = remote.currentTime,
                    duration = if (remote.duration > 0) remote.duration else duration,
                    lastUpdate = remote.lastUpdate,
                    isFinished = remote.isFinished,
                    synced = true
                )
            )
            return remote.currentTime
        }
        return local?.currentTime ?: 0.0
    }

    /**
     * Pushes every unsynced entry. For each one the server's copy is read first:
     * a newer server lastUpdate wins and is written back locally without pushing.
     */
    suspend fun syncProgress() {
        for (local in db.progress().unsynced()) {
            try {
                val remote = api.progress(local.itemId)
                if (remote != null && remote.lastUpdate > local.lastUpdate) {
                    db.progress().upsert(
                        ProgressEntity(
                            itemId = local.itemId,
                            currentTime = remote.currentTime,
                            duration = if (remote.duration > 0) remote.duration else local.duration,
                            lastUpdate = remote.lastUpdate,
                            isFinished = remote.isFinished,
                            synced = true
                        )
                    )
                    continue
                }
                api.pushProgress(
                    local.itemId,
                    ProgressUpdateDto(
                        currentTime = local.currentTime,
                        duration = local.duration,
                        progress = local.fraction,
                        isFinished = if (local.isFinished) true else null
                    )
                )
                db.progress().markSynced(local.itemId, local.lastUpdate)
            } catch (e: Exception) {
                Log.w(TAG, "Progress sync failed for ${local.itemId}", e)
            }
        }
    }

    private companion object {
        const val TAG = "Repository"
        const val SEARCH_LIMIT = 10

        /** Matches the server's default markAsFinishedTimeRemaining. */
        const val FINISHED_SLACK_SECONDS = 10.0
    }
}

// ---- DTO mapping -----------------------------------------------------------

/** Server filenames are used verbatim on disk, so strip anything path-like. */
private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").takeLast(80).ifBlank { "track" }

fun LibraryItemDto.toBookEntity(): BookEntity = BookEntity(
    itemId = id,
    title = media?.metadata?.title.orEmpty().ifBlank { "Untitled" },
    author = media?.metadata?.authorName.orEmpty(),
    duration = media?.duration ?: 0.0
)

/**
 * Maps the expanded item's `media.tracks` to rows. Tracks already carry `ino`
 * in 2.36.1 (the server clones the audio file into the track); the lookup in
 * `audioFiles` is a fallback for older payloads, matching how the official
 * Android app resolves the inode.
 */
/**
 * Maps `media.chapters`. Chapters are optional — many books have none — and the
 * server already stores start/end in seconds from the start of the whole book.
 */
fun LibraryItemDto.toChapterEntities(): List<ChapterEntity> {
    // Sort first and map over the sorted list: the end-fallback below looks at
    // the *next* chapter, which is only meaningful in playback order.
    val sorted = media?.chapters.orEmpty().sortedBy { it.start }
    return sorted.mapIndexed { index, chapter ->
        ChapterEntity(
            itemId = id,
            chapterIndex = index,
            start = chapter.start,
            // Some books carry a zero end on the last chapter; fall back to the
            // next chapter's start, or the book duration.
            end = chapter.end.takeIf { it > chapter.start }
                ?: sorted.getOrNull(index + 1)?.start
                ?: media?.duration
                ?: chapter.start,
            title = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
        )
    }
}

fun LibraryItemDto.toTrackEntities(): List<TrackEntity> {
    val media = media ?: return emptyList()
    val byPath = media.audioFiles.associateBy { it.metadata?.path }
    var offset = 0.0
    return media.tracks
        .sortedBy { it.index ?: 0 }
        .mapIndexedNotNull { position, track ->
            val ino = track.ino ?: byPath[track.metadata?.path]?.ino ?: return@mapIndexedNotNull null
            val duration = track.duration ?: 0.0
            val startOffset = track.startOffset ?: offset
            offset = startOffset + duration
            TrackEntity(
                itemId = id,
                trackIndex = position,
                ino = ino,
                startOffset = startOffset,
                duration = duration,
                mimeType = track.mimeType,
                fileName = sanitizeFileName(
                    track.metadata?.filename?.takeIf { it.isNotBlank() }
                        ?: "track-$position${track.metadata?.ext.orEmpty()}"
                ),
                fileSize = track.metadata?.size ?: 0L
            )
        }
}
