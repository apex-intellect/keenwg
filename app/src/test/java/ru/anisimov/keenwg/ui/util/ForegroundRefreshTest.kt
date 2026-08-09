package ru.anisimov.keenwg.ui.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRefreshTest {
    @Test fun `foreground cadence is serialized and stops with lifecycle scope`() = runTest {
        val rciTimes = mutableListOf<Long>()
        val collectorTimes = mutableListOf<Long>()
        val job = startForegroundRefresh(
            rciRefresh = { rciTimes += testScheduler.currentTime },
            collectorRefresh = { collectorTimes += testScheduler.currentTime },
        )

        runCurrent()
        repeat(4) { advanceTimeBy(15_000); runCurrent() }

        assertEquals(listOf(0L, 15_000L, 30_000L, 45_000L, 60_000L), rciTimes)
        assertEquals(listOf(0L, 60_000L), collectorTimes)
        job.cancel()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(5, rciTimes.size)
    }
}
