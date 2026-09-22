package org.wearabs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object Notifications {
    const val DOWNLOAD_CHANNEL_ID = "downloads"
    const val DOWNLOAD_NOTIFICATION_ID = 4711

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Audiobook downloads" }
        )
    }
}
