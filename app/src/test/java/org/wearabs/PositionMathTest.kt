package org.wearabs

import kotlin.test.assertEquals
import org.junit.Test
import org.wearabs.data.TrackEntity
import org.wearabs.player.globalPosition
import org.wearabs.player.seekTargetFor
import org.wearabs.ui.formatDuration

private fun track(index: Int, offset: Double, duration: Double) = TrackEntity(
    itemId = "li_1",
    trackIndex = index,
    ino = "ino$index",
    startOffset = offset,
    duration = duration,
    mimeType = "audio/mpeg",
    fileName = "0$index.mp3",
    fileSize = 0L
)

/** Three 20s tracks: 0-20, 20-40, 40-60. */
private val tracks = listOf(track(0, 0.0, 20.0), track(1, 20.0, 20.0), track(2, 40.0, 20.0))

class PositionMathTest {

    @Test
    fun `global position maps into the right track`() {
        assertEquals(0 to 0L, seekTargetFor(tracks, 0.0).let { it.trackIndex to it.positionMs })
        assertEquals(0 to 5_000L, seekTargetFor(tracks, 5.0).let { it.trackIndex to it.positionMs })
        assertEquals(1 to 0L, seekTargetFor(tracks, 20.0).let { it.trackIndex to it.positionMs })
        assertEquals(1 to 15_000L, seekTargetFor(tracks, 35.0).let { it.trackIndex to it.positionMs })
        assertEquals(2 to 19_000L, seekTargetFor(tracks, 59.0).let { it.trackIndex to it.positionMs })
    }

    @Test
    fun `out of range positions are clamped`() {
        assertEquals(0, seekTargetFor(tracks, -10.0).trackIndex)
        assertEquals(0L, seekTargetFor(tracks, -10.0).positionMs)

        val past = seekTargetFor(tracks, 10_000.0)
        assertEquals(2, past.trackIndex)
        assertEquals(20_000L, past.positionMs)
    }

    @Test
    fun `empty track list is safe`() {
        assertEquals(0, seekTargetFor(emptyList(), 42.0).trackIndex)
        assertEquals(0L, seekTargetFor(emptyList(), 42.0).positionMs)
    }

    @Test
    fun `round trip between global and per-track position`() {
        for (seconds in listOf(0.0, 7.5, 20.0, 41.25, 59.9)) {
            val target = seekTargetFor(tracks, seconds)
            val back = globalPosition(tracks, target.trackIndex, target.positionMs)
            assertEquals(seconds, back, 0.002)
        }
    }

    @Test
    fun `durations are formatted for the watch face`() {
        assertEquals("00:00", formatDuration(0.0))
        assertEquals("01:05", formatDuration(65.0))
        assertEquals("1:00:00", formatDuration(3600.0))
        assertEquals("2:03:04", formatDuration(7384.0))
        assertEquals("--:--", formatDuration(-1.0))
    }
}
