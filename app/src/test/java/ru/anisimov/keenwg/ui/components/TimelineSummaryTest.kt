package ru.anisimov.keenwg.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.domain.model.PeerStatsPoint

class TimelineSummaryTest {
    @Test fun `summary reports observed online time and missing coverage`() {
        val stats = stats(
            observed = 86_400 - 2_220,
            online = 31_320,
            points = emptyList(),
        )

        val summary = timelineSummaryFacts(stats)
        assertEquals(86_400, summary.periodSeconds)
        assertEquals(31_320, summary.onlineSeconds)
        assertEquals(2_220, summary.missingSeconds)
    }

    @Test fun `zero coverage is unavailable history rather than offline`() {
        val summary = timelineSummaryFacts(stats(observed = 0, online = 0, points = emptyList()))

        assertEquals(0, summary.observedSeconds)
        assertEquals(86_400, summary.missingSeconds)
    }

    @Test fun `builder preserves gaps and partial observed buckets`() {
        val points = listOf(
            PeerStatsPoint(0, 60, 60, 0, 0),
            PeerStatsPoint(120, 30, 15, 0, 0),
        )

        val slices = buildTimelineSlices(stats(from = 0, to = 240, observed = 90, online = 75, points = points))

        assertEquals(TimelineState.ONLINE, slices[0].state)
        assertEquals(TimelineState.NO_DATA, slices[1].state)
        assertEquals(TimelineState.MIXED, slices[2].state)
        assertEquals(0.5f, slices[2].coverageFraction)
        assertEquals(TimelineState.NO_DATA, slices.last().state)
    }

    private fun stats(
        from: Long = 0,
        to: Long = 86_400,
        observed: Long,
        online: Long,
        points: List<PeerStatsPoint>,
    ) = PeerStats(from, to, observed, online, null, 0, 0, 0, observed.toDouble() / (to - from), points)
}
