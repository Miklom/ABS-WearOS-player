package org.wearabs.net

import kotlinx.serialization.Serializable

/**
 * Wire types for the Audiobookshelf API (verified against server tag v2.36.1).
 * Only the fields this app actually uses are declared; the Json instance is
 * configured to ignore everything else.
 */

/**
 * POST /login body. Passport's local strategy reads exactly these two fields.
 * The request also carries `x-return-tokens: true`, which makes the server put
 * the refresh token in the JSON instead of an httpOnly cookie.
 */
@Serializable
data class LoginRequest(val username: String, val password: String)

/** POST /login and POST /auth/refresh response (only the parts this app uses). */
@Serializable
data class LoginResponse(
    val user: LoginUserDto,
    /** Library the server suggests for this user; saves a /api/libraries call. */
    val userDefaultLibraryId: String? = null
)

@Serializable
data class LoginUserDto(
    val id: String? = null,
    val username: String? = null,
    /** Bearer token for /api routes. Expires after an hour by default. */
    val accessToken: String? = null,
    /** Only present when the request asked for it. Rotated on every refresh. */
    val refreshToken: String? = null
)

/** GET /api/libraries -> { "libraries": [...] } */
@Serializable
data class LibrariesResponse(val libraries: List<LibraryDto> = emptyList())

@Serializable
data class LibraryDto(
    val id: String,
    val name: String? = null,
    val mediaType: String? = null
)

/**
 * GET /api/libraries/{id}/search?q=&limit=
 * Returns matches grouped by kind; only "book" carries library items.
 */
@Serializable
data class SearchResponse(val book: List<BookMatchDto> = emptyList())

@Serializable
data class BookMatchDto(val libraryItem: LibraryItemDto)

/** GET /api/items/{id}?expanded=1 */
@Serializable
data class LibraryItemDto(
    val id: String,
    val mediaType: String? = null,
    val media: MediaDto? = null
)

@Serializable
data class MediaDto(
    val metadata: MetadataDto? = null,
    /** Total book length in seconds. */
    val duration: Double? = null,
    /** Playback tracks: audio files plus startOffset/contentUrl, in play order. */
    val tracks: List<TrackDto> = emptyList(),
    val audioFiles: List<AudioFileDto> = emptyList(),
    /** Chapter marks in seconds from the start of the book. */
    val chapters: List<ChapterDto> = emptyList()
)

@Serializable
data class ChapterDto(
    val id: Int? = null,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String? = null
)

@Serializable
data class MetadataDto(
    val title: String? = null,
    /** Pre-joined author names, present on the expanded/minified metadata. */
    val authorName: String? = null
)

@Serializable
data class TrackDto(
    val index: Int? = null,
    /** Inode used as the file id in /api/items/{id}/file/{ino}/download. */
    val ino: String? = null,
    val startOffset: Double? = null,
    val duration: Double? = null,
    val mimeType: String? = null,
    val metadata: FileMetadataDto? = null,
    val contentUrl: String? = null
)

@Serializable
data class AudioFileDto(
    val index: Int? = null,
    val ino: String? = null,
    val metadata: FileMetadataDto? = null
)

@Serializable
data class FileMetadataDto(
    val filename: String? = null,
    val ext: String? = null,
    val path: String? = null,
    val relPath: String? = null,
    val size: Long? = null
)

/** GET /api/me/progress/{itemId} — 404 when the server has no progress yet. */
@Serializable
data class MediaProgressDto(
    val libraryItemId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    /** Server-side updatedAt in epoch millis. The server sets this; clients cannot. */
    val lastUpdate: Long = 0L
)

/** PATCH /api/me/progress/{itemId} body. */
@Serializable
data class ProgressUpdateDto(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    /**
     * Only sent when the book really is finished. Sending `false` while the
     * server has the book marked finished makes the server reset currentTime
     * to 0 (see MediaProgress.applyProgressUpdate in the server source), which
     * would throw away the position we are trying to push.
     */
    val isFinished: Boolean? = null
)
