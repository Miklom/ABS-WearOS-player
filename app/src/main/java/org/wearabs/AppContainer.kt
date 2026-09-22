package org.wearabs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.wearabs.data.AppDatabase
import org.wearabs.data.AuthStore
import org.wearabs.data.Repository
import org.wearabs.net.AbsApi

/** Hand-rolled singletons. No DI framework, by design. */
class AppContainer(context: Context) {
    val authStore: AuthStore = AuthStore(context)
    val api: AbsApi = AbsApi(authStore)
    val database: AppDatabase = AppDatabase.build(context)
    val repository: Repository = Repository(context.applicationContext, api, authStore, database)

    /**
     * Outlives Activities and Services. Saving the listening position on
     * shutdown must not be cancelled half-way by the component going away.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
