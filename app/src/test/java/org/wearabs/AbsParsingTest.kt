package org.wearabs

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.Test
import org.wearabs.net.LibraryItemDto
import org.wearabs.net.MediaProgressDto
import org.wearabs.net.SearchResponse
import org.wearabs.data.toBookEntity
import org.wearabs.data.toTrackEntities

/**
 * The payloads below are shaped after what Audiobookshelf 2.36.1 actually
 * returns (LibraryItem.toOldJSONExpanded / libraryItemsBookFilters.search /
 * MediaProgress.getOldMediaProgress), trimmed to the fields this app reads.
 */
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class AbsParsingTest {

    @Test
    fun `search response unwraps the book matches`() {
        val payload = """
        {
          "book": [
            { "libraryItem": {
                "id": "li_abc",
                "mediaType": "book",
                "media": {
                  "metadata": { "title": "Dune", "authorName": "Frank Herbert" },
                  "duration": 7200.5
                }
            } }
          ],
          "narrators": [], "tags": [], "genres": [], "series": [], "authors": []
        }
        """.trimIndent()

        val items = json.decodeFromString<SearchResponse>(payload).book.map { it.libraryItem }
        assertEquals(1, items.size)

        val book = items.first().toBookEntity()
        assertEquals("li_abc", book.itemId)
        assertEquals("Dune", book.title)
        assertEquals("Frank Herbert", book.author)
        assertEquals(7200.5, book.duration, 0.001)
    }

    @Test
    fun `multi-file book maps tracks with running offsets`() {
        val payload = """
        {
          "id": "li_multi",
          "media": {
            "metadata": { "title": "Three Parter", "authorName": "A. Author" },
            "duration": 60.0,
            "audioFiles": [
              { "index": 1, "ino": "111", "metadata": { "filename": "01.mp3", "path": "/b/01.mp3", "size": 100 } },
              { "index": 2, "ino": "222", "metadata": { "filename": "02.mp3", "path": "/b/02.mp3", "size": 200 } },
              { "index": 3, "ino": "333", "metadata": { "filename": "03.mp3", "path": "/b/03.mp3", "size": 300 } }
            ],
            "tracks": [
              { "index": 1, "ino": "111", "startOffset": 0,  "duration": 20, "mimeType": "audio/mpeg",
                "metadata": { "filename": "01.mp3", "path": "/b/01.mp3", "size": 100 } },
              { "index": 2, "ino": "222", "startOffset": 20, "duration": 20, "mimeType": "audio/mpeg",
                "metadata": { "filename": "02.mp3", "path": "/b/02.mp3", "size": 200 } },
              { "index": 3, "ino": "333", "startOffset": 40, "duration": 20, "mimeType": "audio/mpeg",
                "metadata": { "filename": "03.mp3", "path": "/b/03.mp3", "size": 300 } }
            ]
          }
        }
        """.trimIndent()

        val tracks = json.decodeFromString<LibraryItemDto>(payload).toTrackEntities()

        assertEquals(listOf(0, 1, 2), tracks.map { it.trackIndex })
        assertEquals(listOf("111", "222", "333"), tracks.map { it.ino })
        assertEquals(listOf(0.0, 20.0, 40.0), tracks.map { it.startOffset })
        assertEquals(listOf(100L, 200L, 300L), tracks.map { it.fileSize })
    }

    @Test
    fun `single m4b book maps to one track`() {
        val payload = """
        {
          "id": "li_single",
          "media": {
            "metadata": { "title": "One File", "authorName": "B. Writer" },
            "duration": 3600.0,
            "audioFiles": [
              { "index": 1, "ino": "900", "metadata": { "filename": "book.m4b", "path": "/b/book.m4b", "size": 5000 } }
            ],
            "tracks": [
              { "index": 1, "ino": "900", "startOffset": 0, "duration": 3600, "mimeType": "audio/mp4",
                "metadata": { "filename": "book.m4b", "path": "/b/book.m4b", "size": 5000 } }
            ]
          }
        }
        """.trimIndent()

        val tracks = json.decodeFromString<LibraryItemDto>(payload).toTrackEntities()
        assertEquals(1, tracks.size)
        assertEquals("900", tracks[0].ino)
        assertEquals("book.m4b", tracks[0].fileName)
        assertEquals(3600.0, tracks[0].duration, 0.001)
    }

    @Test
    fun `track without ino falls back to matching the audio file by path`() {
        val payload = """
        {
          "id": "li_old",
          "media": {
            "metadata": { "title": "Legacy", "authorName": "C. Coder" },
            "duration": 10.0,
            "audioFiles": [
              { "index": 1, "ino": "42", "metadata": { "filename": "a.mp3", "path": "/b/a.mp3", "size": 10 } }
            ],
            "tracks": [
              { "index": 1, "startOffset": 0, "duration": 10, "mimeType": "audio/mpeg",
                "metadata": { "filename": "a.mp3", "path": "/b/a.mp3", "size": 10 } }
            ]
          }
        }
        """.trimIndent()

        val tracks = json.decodeFromString<LibraryItemDto>(payload).toTrackEntities()
        assertEquals(listOf("42"), tracks.map { it.ino })
    }

    @Test
    fun `media progress carries the server lastUpdate`() {
        val payload = """
        {
          "id": "mp_1", "libraryItemId": "li_abc", "mediaItemType": "book",
          "duration": 7200.5, "progress": 0.25, "currentTime": 1800.0,
          "isFinished": false, "hideFromContinueListening": false,
          "ebookLocation": null, "ebookProgress": null,
          "lastUpdate": 1758400000000, "startedAt": 1758300000000, "finishedAt": null
        }
        """.trimIndent()

        val progress = json.decodeFromString<MediaProgressDto>(payload)
        assertEquals(1800.0, progress.currentTime, 0.001)
        assertEquals(1758400000000L, progress.lastUpdate)
        assertEquals(false, progress.isFinished)
    }

    @Test
    fun `a book with no audio files yields no tracks`() {
        val payload = """{ "id": "li_empty", "media": { "metadata": { "title": "Ebook only" } } }"""
        val item = json.decodeFromString<LibraryItemDto>(payload)
        assertEquals(emptyList(), item.toTrackEntities())
        assertEquals(0.0, item.toBookEntity().duration, 0.001)
        assertNull(item.media?.duration)
    }
}
