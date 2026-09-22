package org.wearabs.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.getSystemService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Asks for a fast network before downloading.
 *
 * On a paired watch the default route is the Bluetooth proxy through the phone,
 * which is far too slow for audiobook files. `requestNetwork` makes the system
 * bring up Wi-Fi and hands back a [Network] that callers bind their sockets to.
 * The callback stays registered for as long as the network is held, otherwise
 * the platform tears Wi-Fi down again.
 */
class HighBandwidthNetwork(context: Context) {

    private val connectivity = context.applicationContext.getSystemService<ConnectivityManager>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True when the acquired network is not the requested high-bandwidth one. */
    var isFallback: Boolean = false
        private set

    /**
     * Returns a Wi-Fi network, or an unmetered one, or finally the active
     * network — in which case [isFallback] is true and the caller should tell
     * the user the download will be slow. Null means there is no network at all.
     */
    suspend fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Network? {
        val manager = connectivity ?: return null

        request(manager, wifiRequest(), timeoutMs)?.let {
            isFallback = false
            return it
        }
        Log.i(TAG, "No Wi-Fi available, trying any unmetered network")
        request(manager, unmeteredRequest(), timeoutMs)?.let {
            isFallback = false
            return it
        }

        Log.w(TAG, "Falling back to the default network — downloads will be slow")
        isFallback = true
        return manager.activeNetwork
    }

    fun release() {
        val manager = connectivity ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun wifiRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private fun unmeteredRequest(): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        .build()

    private suspend fun request(
        manager: ConnectivityManager,
        request: NetworkRequest,
        timeoutMs: Long
    ): Network? = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (resumed.compareAndSet(false, true)) continuation.resume(network)
            }

            override fun onUnavailable() {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { manager.unregisterNetworkCallback(this) }
                    if (callback === this) callback = null
                    continuation.resume(null)
                }
            }
        }

        // Keep the previous request, if any, from leaking.
        release()
        callback = networkCallback

        try {
            manager.requestNetwork(request, networkCallback, timeoutMs.toInt())
        } catch (e: SecurityException) {
            Log.w(TAG, "requestNetwork denied", e)
            callback = null
            if (resumed.compareAndSet(false, true)) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation { release() }
    }

    private companion object {
        const val TAG = "HighBandwidthNetwork"
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
