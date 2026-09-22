package org.wearabs

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.Test
import org.wearabs.data.ChapterEntity
import org.wearabs.data.toChapterEntities
import org.wearabs.net.LibraryItemDto
import org.wearabs.player.chapterIndexAt
import org.wearabs.player.nextChapterTarget
import org.wearabs.player.previousChapterTarget

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

private fun chapter(index: Int, start: Double, end: Double) =
    ChapterEntity("li_1", index, start, end, "Chapter ${index + 1}")

/** 0-100, 100-200, 200-300. */
private val chapters = listOf(chapter(0, 0.0, 100.0), chapter(1, 100.0, 200.0), chapter(2, 200.0, 300.0))

class ChapterTest {

    @Test
    fun `position maps to the containing chapter`() {
        assertEquals(0, chapterIndexAt(chapters, 0.0))
        assertEquals(0, chapterIndexAt(chapters, 99.9))
        assertEquals(1, chapterIndexAt(chapters, 100.0))
        assertEquals(2, chapterIndexAt(chapters, 299.0))
        // Past the end still reports the last chapter rather than null.
        assertEquals(2, chapterIndexAt(chapters, 5000.0))
        assertNull(chapterIndexAt(emptyList(), 42.0))
    }

    @Test
    fun `previous restarts the current chapter once past the threshold`() {
        // 10s into chapter 2 -> back to its own start, not the previous one.
        assertEquals(100.0, previousChapterTarget(chapters, 110.0))
        // 1s in -> step back to the previous chapter.
        assertEquals(0.0, previousChapterTarget(chapters, 101.0))
        // Exactly on the boundary counts as "just started".
        assertEquals(0.0, previousChapterTarget(chapters, 100.0))
    }

    @Test
    fun `previous in the first chapter stays at zero`() {
        assertEquals(0.0, previousChapterTarget(chapters, 2.0))
        assertEquals(0.0, previousChapterTarget(chapters, 50.0))
        assertNull(previousChapterTarget(emptyList(), 50.0))
    }

    @Test
    fun `next steps forward and stops at the last chapter`() {
        assertEquals(100.0, nextChapterTarget(chapters, 10.0))
        assertEquals(200.0, nextChapterTarget(chapters, 150.0))
        assertNull(nextChapterTarget(chapters, 250.0))
        assertNull(nextChapterTarget(emptyList(), 10.0))
    }

    /** Shaped after media.chapters in 2.36.1: { id, start, end, title }. */
    @Test
    fun `chapters are mapped in playback order`() {
        val payload = """
        {
          "id": "li_1",
          "media": {
            "metadata": { "title": "Chaptered" },
            "duration": 300,
            "chapters": [
              { "id": 2, "start": 100, "end": 200, "title": "Two" },
              { "id": 0, "start": 0,   "end": 100, "title": "One" },
              { "id": 3, "start": 200, "end": 300, "title": "Three" }
            ]
          }
        }
        """.trimIndent()

        val mapped = json.decodeFromString<LibraryItemDto>(payload).toChapterEntities()
        assertEquals(listOf(0, 1, 2), mapped.map { it.chapterIndex })
        assertEquals(listOf("One", "Two", "Three"), mapped.map { it.title })
        assertEquals(listOf(0.0, 100.0, 200.0), mapped.map { it.start })
    }

    /**
     * A zero `end` on the final chapter has to fall back to the *next sorted*
     * chapter's start, or the book duration — not to whatever happened to be
     * next in the unsorted payload.
     */
    @Test
    fun `a missing chapter end falls back sensibly`() {
        val payload = """
        {
          "id": "li_2",
          "media": {
            "metadata": { "title": "Ragged" },
            "duration": 300,
            "chapters": [
              { "id": 1, "start": 100, "end": 0, "title": "Two" },
              { "id": 0, "start": 0,   "end": 0, "title": "One" },
              { "id": 2, "start": 200, "end": 0, "title": "Three" }
            ]
          }
        }
        """.trimIndent()

        val mapped = json.decodeFromString<LibraryItemDto>(payload).toChapterEntities()
        assertEquals(listOf(100.0, 200.0, 300.0), mapped.map { it.end })
    }

    @Test
    fun `chapters without titles get numbered ones`() {
        val payload = """
        { "id": "li_3", "media": { "metadata": { "title": "Blank" }, "duration": 20,
          "chapters": [ { "start": 0, "end": 10, "title": "" }, { "start": 10, "end": 20 } ] } }
        """.trimIndent()

        val mapped = json.decodeFromString<LibraryItemDto>(payload).toChapterEntities()
        assertEquals(listOf("Chapter 1", "Chapter 2"), mapped.map { it.title })
    }

    @Test
    fun `a book with no chapters yields none`() {
        val payload = """{ "id": "li_4", "media": { "metadata": { "title": "Plain" }, "duration": 10 } }"""
        assertEquals(emptyList(), json.decodeFromString<LibraryItemDto>(payload).toChapterEntities())
    }
}
