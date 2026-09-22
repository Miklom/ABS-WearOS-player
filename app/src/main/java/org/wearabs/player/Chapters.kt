package org.wearabs.player

import org.wearabs.data.ChapterEntity

/** Past this far into a chapter, "previous" restarts it instead of stepping back. */
const val RESTART_CHAPTER_SECONDS = 3.0

/** Index of the chapter containing [position], or null when there are none. */
fun chapterIndexAt(chapters: List<ChapterEntity>, position: Double): Int? {
    if (chapters.isEmpty()) return null
    // Before the first mark counts as the first chapter rather than "none".
    return chapters.indexOfLast { it.start <= position }.coerceAtLeast(0)
}

/**
 * Where "previous chapter" should jump to: the start of the current chapter
 * when already playing into it, otherwise the start of the one before — the
 * convention every audio player uses. Null when there are no chapters.
 */
fun previousChapterTarget(chapters: List<ChapterEntity>, position: Double): Double? {
    val index = chapterIndexAt(chapters, position) ?: return null
    val current = chapters[index]
    val restartCurrent = position - current.start > RESTART_CHAPTER_SECONDS
    return if (restartCurrent) current.start else (chapters.getOrNull(index - 1) ?: current).start
}

/** Start of the next chapter, or null when already in the last one. */
fun nextChapterTarget(chapters: List<ChapterEntity>, position: Double): Double? {
    val index = chapterIndexAt(chapters, position) ?: return null
    return chapters.getOrNull(index + 1)?.start
}
