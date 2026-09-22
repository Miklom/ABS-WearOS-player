package org.wearabs

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.wearabs.net.Covers
import org.wearabs.work.SyncWorker

class WearAbsApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Push anything that was left unsynced while the watch was offline.
        SyncWorker.enqueueNow(this)
        SyncWorker.enqueuePeriodic(this, ExistingPeriodicWorkPolicy.KEEP)
    }

    /** Cover art needs the same bearer auth as every other /api call. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        Covers.imageLoader(this, container.authStore)

    companion object {
        private lateinit var instance: WearAbsApp

        /** Available to Services and Workers, which are constructed by the system. */
        fun container(): AppContainer = instance.container
    }
}
