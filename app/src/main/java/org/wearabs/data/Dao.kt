package org.wearabs.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books WHERE itemId = :itemId")
    suspend fun find(itemId: String): BookEntity?

    @Query("SELECT * FROM books WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE downloaded = 1 ORDER BY addedAt DESC")
    fun observeDownloaded(): Flow<List<BookEntity>>

    @Query("UPDATE books SET downloaded = :downloaded WHERE itemId = :itemId")
    suspend fun setDownloaded(itemId: String, downloaded: Boolean)

    @Query("DELETE FROM books WHERE itemId = :itemId")
    suspend fun delete(itemId: String)
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE itemId = :itemId ORDER BY trackIndex ASC")
    suspend fun forBook(itemId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE itemId = :itemId ORDER BY trackIndex ASC")
    fun observeForBook(itemId: String): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET localPath = :localPath WHERE itemId = :itemId AND trackIndex = :trackIndex")
    suspend fun setLocalPath(itemId: String, trackIndex: Int, localPath: String?)

    @Query("UPDATE tracks SET fileSize = :fileSize WHERE itemId = :itemId AND trackIndex = :trackIndex")
    suspend fun setFileSize(itemId: String, trackIndex: Int, fileSize: Long)

    @Query("DELETE FROM tracks WHERE itemId = :itemId")
    suspend fun deleteForBook(itemId: String)

    /** Replaces a book's track list, keeping any already-downloaded file paths. */
    @Transaction
    suspend fun replaceKeepingLocalFiles(itemId: String, tracks: List<TrackEntity>) {
        val existing = forBook(itemId).associateBy { it.ino }
        deleteForBook(itemId)
        upsertAll(tracks.map { track -> track.copy(localPath = existing[track.ino]?.localPath) })
    }
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun find(itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE synced = 0")
    suspend fun unsynced(): List<ProgressEntity>

    @Query("UPDATE progress SET synced = 1 WHERE itemId = :itemId AND lastUpdate = :lastUpdate")
    suspend fun markSynced(itemId: String, lastUpdate: Long)

    @Query("DELETE FROM progress WHERE itemId = :itemId")
    suspend fun delete(itemId: String)
}
