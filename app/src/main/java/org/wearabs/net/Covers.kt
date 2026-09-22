package org.wearabs.net

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import okhttp3.OkHttpClient
import org.wearabs.data.AuthStore

/**
 * Cover art loading.
 *
 * `GET /api/items/{id}/cover` sits behind the same bearer auth as the rest of
 * /api, and it can resize server-side — which matters a lot here, because an
 * un-resized cover is megabytes and the watch may be on the Bluetooth proxy.
 */
object Covers {

    /** Wide enough for a full-screen background on a 1.4" watch. */
    const val LARGE = 384

    /** Row icon in search results. */
    const val THUMB = 96

    /** Deliberately tiny: upscaling a small image is a cheap, good-looking blur. */
    const val BACKDROP = 96

    fun url(serverUrl: String, itemId: String, width: Int): String =
        "$serverUrl/api/items/$itemId/cover?width=$width&format=jpeg"

    /**
     * An ImageLoader that authenticates like the rest of the app. Tokens are read
     * per request, so a refresh mid-session is picked up without rebuilding it.
     */
    fun imageLoader(context: Context, authStore: AuthStore): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = authStore.current?.accessToken
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.20).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(24L * 1024 * 1024)
                    .build()
            }
            // No crossfade: the fade invalidates every frame while it runs, and
            // in a scrolling list that lands on the frames that can least
            // afford it. Covers come from memory or local disk anyway.
            .build()
    }
}
