package org.wearabs.net

import android.net.Network
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.wearabs.data.AuthStore
import org.wearabs.data.Session

/** Thrown for any non-2xx response that the caller is not expected to handle. */
class AbsHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** The refresh token is gone or rejected — the user has to log in again. */
class SessionExpiredException : IOException("Session expired")

/** Login failed; [message] is safe to show on screen. */
class LoginFailedException(message: String) : IOException(message)

/**
 * Audiobookshelf client. The user logs in with server URL, username and
 * password; the resulting access token is sent as a bearer token and is
 * transparently refreshed when the server rejects it.
 */
class AbsApi(private val authStore: AuthStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Serialises refreshes so a burst of 401s triggers only one exchange. */
    private val refreshMutex = Mutex()

    // ---- Login -------------------------------------------------------------

    /**
     * POST /login. On success the session is stored and every later call uses it.
     *
     * @param serverUrl as typed by the user; the scheme is added if missing.
     */
    suspend fun login(serverUrl: String, username: String, password: String) {
        val baseUrl = normalizeServerUrl(serverUrl)
        val payload = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/login")
            .header("Accept", "application/json")
            // Without this the refresh token comes back as an httpOnly cookie,
            // which is no use to a native client.
            .header("x-return-tokens", "true")
            .post(payload)
            .build()

        val body = execute(client, request).use { response ->
            when (response.code) {
                401 -> throw LoginFailedException("Wrong username or password")
                429 -> throw LoginFailedException("Too many attempts, wait a minute")
                404 -> throw LoginFailedException("Not an Audiobookshelf server")
                else -> {
                    if (!response.isSuccessful) {
                        throw LoginFailedException("Server error ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
            }
        }

        val parsed = runCatching { json.decodeFromString<LoginResponse>(body) }
            .getOrElse { throw LoginFailedException("Unexpected response from server") }

        val accessToken = parsed.user.accessToken
            ?: throw LoginFailedException("Server returned no access token")
        val refreshToken = parsed.user.refreshToken
            ?: throw LoginFailedException("Server returned no refresh token")

        authStore.save(
            Session(
                serverUrl = baseUrl,
                username = parsed.user.username ?: username,
                accessToken = accessToken,
                refreshToken = refreshToken,
                defaultLibraryId = parsed.userDefaultLibraryId
            )
        )
    }

    fun logout() = authStore.clear()

    /**
     * POST /auth/refresh with the stored refresh token. Returns the renewed
     * session, or null when the server refuses it.
     */
    private suspend fun refreshSession(stale: Session): Session? = refreshMutex.withLock {
        // Another call may already have refreshed while this one waited.
        val current = authStore.current ?: return null
        if (current.accessToken != stale.accessToken) return current

        val request = Request.Builder()
            .url("${current.serverUrl}/auth/refresh")
            .header("Accept", "application/json")
            .header("x-refresh-token", current.refreshToken)
            .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val body = execute(client, request).use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Refresh rejected with ${response.code}")
                return null
            }
            response.body?.string().orEmpty()
        }

        val parsed = runCatching { json.decodeFromString<LoginResponse>(body) }.getOrNull()
        val accessToken = parsed?.user?.accessToken ?: return null
        // The server rotates refresh tokens, so keep whichever it just handed back.
        val refreshToken = parsed.user.refreshToken ?: current.refreshToken

        authStore.updateTokens(accessToken, refreshToken)
        return authStore.current
    }

    // ---- Authorised requests ----------------------------------------------

    private fun buildRequest(
        session: Session,
        path: String,
        configure: Request.Builder.() -> Unit
    ): Request = Request.Builder()
        .url(session.serverUrl + path)
        .header("Authorization", "Bearer ${session.accessToken}")
        .header("Accept", "application/json")
        .apply(configure)
        .build()

    /**
     * Sends an authorised request, refreshing the access token once if the
     * server says it has expired.
     */
    private suspend fun send(
        httpClient: OkHttpClient,
        path: String,
        configure: Request.Builder.() -> Unit
    ): Response {
        val session = authStore.current ?: throw SessionExpiredException()
        val response = execute(httpClient, buildRequest(session, path, configure))
        if (response.code != 401) return response

        response.close()
        val refreshed = refreshSession(session) ?: run {
            authStore.clear()
            throw SessionExpiredException()
        }
        return execute(httpClient, buildRequest(refreshed, path, configure))
    }

    // ---- JSON endpoints ----------------------------------------------------

    /** GET /api/libraries — the first library is the one this app uses. */
    suspend fun firstLibraryId(): String? {
        val body = getString("/api/libraries")
        return json.decodeFromString<LibrariesResponse>(body).libraries.firstOrNull()?.id
    }

    /** GET /api/libraries/{libraryId}/search?q={query}&limit={limit} */
    suspend fun search(libraryId: String, query: String, limit: Int = 10): List<LibraryItemDto> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = getString("/api/libraries/$libraryId/search?q=$q&limit=$limit")
        return json.decodeFromString<SearchResponse>(body).book.map { it.libraryItem }
    }

    /** GET /api/items/{itemId}?expanded=1 */
    suspend fun item(itemId: String): LibraryItemDto =
        json.decodeFromString(getString("/api/items/$itemId?expanded=1"))

    /** GET /api/me/progress/{itemId}. Null when the server has no progress (404). */
    suspend fun progress(itemId: String): MediaProgressDto? {
        val body = getStringOrNull("/api/me/progress/$itemId") ?: return null
        return json.decodeFromString(body)
    }

    /** PATCH /api/me/progress/{itemId}. The server replies 200 with an empty body. */
    suspend fun pushProgress(itemId: String, update: ProgressUpdateDto) {
        val payload = json.encodeToString(ProgressUpdateDto.serializer(), update)
            .toRequestBody(JSON_MEDIA_TYPE)
        send(client, "/api/me/progress/$itemId") { patch(payload) }.use { response ->
            if (!response.isSuccessful) {
                throw AbsHttpException(response.code, response.body?.string().orEmpty())
            }
        }
    }

    private suspend fun getStringOrNull(path: String): String? {
        send(client, path) { get() }.use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw AbsHttpException(response.code, response.body?.string().orEmpty())
            }
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun getString(path: String): String =
        getStringOrNull(path) ?: throw AbsHttpException(404, path)

    // ---- File download -----------------------------------------------------

    /**
     * Downloads one audio file to [destination], resuming an existing partial
     * file with a Range request. Mirrors the official app's download handling.
     *
     * @param network when non-null, all traffic is pinned to that network so a
     *   Wi-Fi request is not silently served over the Bluetooth proxy.
     * @param onProgress called with (bytesWritten, totalBytes) — totalBytes is
     *   -1 while unknown.
     * @return the number of bytes in the finished file.
     */
    suspend fun downloadFile(
        itemId: String,
        ino: String,
        destination: File,
        network: Network?,
        onProgress: (Long, Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val downloadClient = network?.let {
            client.newBuilder()
                .socketFactory(it.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        it.getAllByName(hostname).toList()
                })
                .build()
        } ?: client

        val path = "/api/items/$itemId/file/$ino/download"
        destination.parentFile?.mkdirs()
        var restarted = false

        while (true) {
            val existing = if (destination.exists()) destination.length() else 0L

            val outcome = send(downloadClient, path) {
                get()
                header("Accept-Encoding", "identity")
                if (existing > 0L) header("Range", "bytes=$existing-")
            }.use { response ->
                val contentRange = response.header("Content-Range")
                when {
                    // Range beyond the file: either we already have all of it, or the
                    // partial file is stale and has to be thrown away.
                    //
                    // Detected by the header, not the status code: Express sets 416
                    // and "Content-Range: bytes */<size>", but Audiobookshelf's
                    // download handler then overwrites the status with 500 in
                    // LibraryItemController.handleDownloadError. Verified against a
                    // live 2.36.1 server, which answers 500 for this case.
                    contentRange != null && contentRange.startsWith(UNSATISFIED_RANGE_PREFIX) -> {
                        val serverSize = contentRange.substringAfterLast('/').toLongOrNull()
                        if (serverSize != null && serverSize == existing) {
                            onProgress(existing, existing)
                            Outcome.Done(existing)
                        } else {
                            if (restarted) {
                                throw AbsHttpException(
                                    response.code,
                                    "unrecoverable range for ${destination.name}"
                                )
                            }
                            Log.w(TAG, "Stale partial file for ${destination.name}, restarting")
                            destination.delete()
                            Outcome.Retry
                        }
                    }

                    !response.isSuccessful ->
                        throw AbsHttpException(response.code, response.body?.string().orEmpty())

                    else -> {
                        // A 200 in reply to a Range request means the server ignored
                        // the range, so the partial bytes cannot be kept.
                        val append = existing > 0L && response.code == 206
                        if (existing > 0L && !append) {
                            if (restarted) {
                                throw AbsHttpException(
                                    response.code,
                                    "resume not honoured for ${destination.name}"
                                )
                            }
                            destination.delete()
                            Outcome.Retry
                        } else {
                            val body = response.body
                                ?: throw AbsHttpException(response.code, "empty body")
                            val base = if (append) existing else 0L
                            val total = if (body.contentLength() >= 0) body.contentLength() + base else -1L
                            var written = base
                            body.byteStream().use { input ->
                                FileOutputStream(destination, append).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        // Blocking reads are not cancellable on their
                                        // own; check so a cancelled download stops
                                        // promptly and leaves a resumable .part file.
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        output.write(buffer, 0, read)
                                        written += read
                                        onProgress(written, total)
                                    }
                                    output.flush()
                                }
                            }
                            Outcome.Done(written)
                        }
                    }
                }
            }

            when (outcome) {
                is Outcome.Done -> return@withContext outcome.bytes
                Outcome.Retry -> restarted = true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        0L
    }

    private sealed interface Outcome {
        data class Done(val bytes: Long) : Outcome
        data object Retry : Outcome
    }

    private companion object {
        const val TAG = "AbsApi"

        /** Header prefix a server uses to say the requested range is unsatisfiable. */
        const val UNSATISFIED_RANGE_PREFIX = "bytes */"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Accepts "abs.example.com", "https://abs.example.com/" and everything between. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/** Bridges OkHttp's callback API to a cancellable coroutine. */
private suspend fun execute(client: OkHttpClient, request: Request): Response =
    suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }
