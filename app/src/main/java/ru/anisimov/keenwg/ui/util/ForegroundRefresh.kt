package ru.anisimov.keenwg.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

fun CoroutineScope.startForegroundRefresh(
    rciRefresh: suspend () -> Unit,
    collectorRefresh: suspend () -> Unit,
    rciIntervalMs: Long = 15_000L,
    collectorIntervalMs: Long = 60_000L,
): Job = launch {
    supervisorScope {
        launch {
            while (isActive) {
                rciRefresh()
                delay(rciIntervalMs)
            }
        }
        launch {
            while (isActive) {
                collectorRefresh()
                delay(collectorIntervalMs)
            }
        }
    }
}
