package org.wearabs

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import org.wearabs.work.SyncWorker

class WearAbsApp : Application() {

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

    companion object {
        private lateinit var instance: WearAbsApp

        /** Available to Services and Workers, which are constructed by the system. */
        fun container(): AppContainer = instance.container
    }
}
