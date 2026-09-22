package org.wearabs.work

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.wearabs.Notifications
import org.wearabs.R
import org.wearabs.WearAbsApp

/**
 * Downloads every audio file of one book, the same way the official Android app
 * does: one request per file against /api/items/{itemId}/file/{ino}/download
 * with a bearer token, resuming partial files with a Range request.
 *
 * The book is only marked downloaded once every file is complete.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = WearAbsApp.container().repository
    private val notifications = applicationContext.getSystemService(NotificationManager::class.java)
    private var lastPublishedAt = 0L

    /** Stable per-book id so two concurrent downloads do not overwrite each other. */
    private val notificationId: Int =
        Notifications.DOWNLOAD_NOTIFICATION_ID + (inputData.getString(KEY_ITEM_ID)?.hashCode() ?: 0).and(0xFFFF)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, false, null)

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()

        val highBandwidth = HighBandwidthNetwork(applicationContext)
        try {
            // Refresh the track list first: it tells us the inodes to download.
            val book = runCatching { repository.refreshBook(itemId) }
                .getOrElse { error ->
                    // Offline, or the server is down: fall back to what is cached
                    // so an interrupted download can still be resumed.
                    Log.w(TAG, "Could not refresh $itemId", error)
                    repository.book(itemId)
                } ?: return retryOrFail()

            val tracks = repository.tracks(itemId)
            if (tracks.isEmpty()) {
                Log.w(TAG, "No tracks for $itemId")
                return Result.failure()
            }

            val network = highBandwidth.acquire()
            val slow = highBandwidth.isFallback
            runCatching { setForeground(foregroundInfo(0, slow, book.title)) }
                .onFailure { Log.w(TAG, "Could not go foreground", it) }
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_SLOW to slow))

            // Known sizes give a real total; unknown ones fall back to per-file weighting.
            val knownTotal = tracks.sumOf { it.fileSize }
            var completedBytes = 0L

            for (track in tracks) {
                val target = repository.trackFile(track)
                if (track.localPath == target.path && target.exists() && target.length() > 0) {
                    completedBytes += target.length()
                    continue
                }

                val part = repository.partFile(track)
                val bytes = repository.api.downloadFile(
                    itemId = itemId,
                    ino = track.ino,
                    destination = part,
                    network = network
                ) { written, _ ->
                    val done = completedBytes + written
                    val percent = when {
                        knownTotal > 0 -> (done * 100 / knownTotal).toInt().coerceIn(0, 100)
                        else -> (track.trackIndex * 100 / tracks.size)
                    }
                    publish(percent, slow, book.title)
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    Log.w(TAG, "Could not move ${part.name} into place")
                    return retryOrFail()
                }
                repository.db.tracks().setLocalPath(itemId, track.trackIndex, target.path)
                if (track.fileSize <= 0L) {
                    repository.db.tracks().setFileSize(itemId, track.trackIndex, bytes)
                }
                completedBytes += bytes
            }

            val complete = repository.reconcileDownloadState(itemId)
            setProgress(workDataOf(KEY_PROGRESS to 100, KEY_SLOW to slow))
            notifications?.cancel(notificationId)
            return if (complete) Result.success() else retryOrFail()
        } catch (e: Exception) {
            Log.w(TAG, "Download of $itemId failed", e)
            return retryOrFail()
        } finally {
            highBandwidth.release()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    /**
     * Called from the download's byte loop, so it must not suspend and must not
     * fire on every buffer — the UI and the notification are updated at most
     * once a second.
     */
    private fun publish(percent: Int, slow: Boolean, title: String?) {
        val now = SystemClock.elapsedRealtime()
        if (percent != 100 && now - lastPublishedAt < PUBLISH_INTERVAL_MS) return
        lastPublishedAt = now
        setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_SLOW to slow))
        notifications?.notify(notificationId, buildNotification(percent, slow, title))
    }

    private fun foregroundInfo(percent: Int, slow: Boolean, title: String?): ForegroundInfo =
        ForegroundInfo(
            notificationId,
            buildNotification(percent, slow, title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

    private fun buildNotification(percent: Int, slow: Boolean, title: String?): Notification =
        NotificationCompat.Builder(applicationContext, Notifications.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: "Downloading")
            .setContentText(if (slow) "No Wi-Fi — this will be slow" else "Downloading…")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "DownloadWorker"
        private const val KEY_ITEM_ID = "itemId"
        private const val MAX_ATTEMPTS = 5
        private const val PUBLISH_INTERVAL_MS = 1_000L

        const val KEY_PROGRESS = "progress"

        /** True when the download fell back off Wi-Fi and will crawl. */
        const val KEY_SLOW = "slow"

        private fun workName(itemId: String) = "download-$itemId"

        fun enqueue(context: Context, itemId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ITEM_ID to itemId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, itemId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
        }

        /** Download state for one book: null when nothing is running. */
        fun observe(context: Context, itemId: String): Flow<DownloadState?> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(workName(itemId))
                .map { infos ->
                    val info = infos.firstOrNull { !it.state.isFinished } ?: return@map null
                    DownloadState(
                        running = info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.ENQUEUED,
                        percent = info.progress.getInt(KEY_PROGRESS, 0),
                        slow = info.progress.getBoolean(KEY_SLOW, false)
                    )
                }
    }
}

data class DownloadState(val running: Boolean, val percent: Int, val slow: Boolean)
