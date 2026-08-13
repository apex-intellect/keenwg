package ru.anisimov.keenwg.data.collector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class CollectorRepositoryTest {
    @Test fun `lineage histories merge overlapping buckets without double counting observation`() = runTest {
        val old = history("a".repeat(64), listOf(point(100, 60, 30, 10, 20), point(160, 60, 60, 5, 7)), resets = 1)
        val fresh = history("b".repeat(64), listOf(point(160, 60, 20, 3, 4), point(220, 60, 60, 8, 9)), resets = 2)
        val gateway = object : CollectorHistoryGateway {
            override suspend fun history(peerId: String, range: HistoryRange) = if (peerId.startsWith('a')) old else fresh
        }

        val merged = CollectorRepository(gateway).history(listOf(old.peerId, fresh.peerId), HistoryRange(100, 280), 280)

        assertEquals(180L, merged.observedSeconds)
        assertEquals(150L, merged.onlineSeconds)
        assertEquals(26L, merged.clientUploadBytes)
        assertEquals(40L, merged.clientDownloadBytes)
        assertEquals(3, merged.counterResets)
        assertEquals(3, merged.points.size)
        assertTrue(merged.points.size <= 288)
    }

    @Test fun `visual point cap preserves totals and the whole selected range`() = runTest {
        val points = (0 until 600).map { index -> point(100L + index * 60L, 60, 30, 1, 2) }
        val source = history("c".repeat(64), points, resets = 0, from = 100, to = 36_100)
        val gateway = object : CollectorHistoryGateway {
            override suspend fun history(peerId: String, range: HistoryRange) = source
        }

        val merged = CollectorRepository(gateway).history(
            listOf(source.peerId), HistoryRange(100, 36_100, "raw"), 36_100,
        )

        assertEquals(36_000L, merged.observedSeconds)
        assertEquals(18_000L, merged.onlineSeconds)
        assertEquals(600L, merged.clientUploadBytes)
        assertEquals(1_200L, merged.clientDownloadBytes)
        assertTrue(merged.points.size <= 288)
        assertEquals(100L, merged.points.first().at)
        assertTrue(merged.points.last().at >= 35_800L)
    }

    private fun point(at: Long, observed: Long, online: Long, upload: Long, download: Long) =
        CollectorPoint(at, observed, online, upload, download)

    private fun history(
        peerId: String,
        points: List<CollectorPoint>,
        resets: Int,
        from: Long = 100,
        to: Long = 280,
    ) = CollectorHistory(
        peerId, from, to, "5m", 0, 0, to - 10, 0, 0, resets, 0.0, points,
    )
}
