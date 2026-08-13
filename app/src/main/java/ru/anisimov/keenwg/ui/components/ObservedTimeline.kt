package ru.anisimov.keenwg.ui.components

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.domain.model.PeerStatsPoint
import ru.anisimov.keenwg.ui.util.durationLabel
import ru.anisimov.keenwg.ui.util.historyPeriodLabel
import kotlin.math.max
import kotlin.math.min

enum class TimelineState { ONLINE, OFFLINE, MIXED, NO_DATA }

data class TimelineSlice(
    val at: Long,
    val durationSeconds: Long,
    val state: TimelineState,
    val coverageFraction: Float,
    val onlineFraction: Float,
)

fun buildTimelineSlices(stats: PeerStats): List<TimelineSlice> {
    if (stats.to <= stats.from) return emptyList()
    val points = stats.points.sortedBy(PeerStatsPoint::at)
    if (points.isEmpty()) return listOf(noData(stats.from, stats.to - stats.from))

    val smallestStep = points.zipWithNext { first, second -> second.at - first.at }
        .filter { it > 0 }
        .minOrNull()
    val observedStep = points.maxOfOrNull(PeerStatsPoint::observedSeconds)?.takeIf { it > 0 }
    val nominalStep = listOfNotNull(smallestStep, observedStep).minOrNull()
        ?.coerceAtLeast(1) ?: (stats.to - stats.from)

    val slices = mutableListOf<TimelineSlice>()
    var cursor = stats.from
    for (point in points) {
        val start = point.at.coerceIn(stats.from, stats.to)
        if (start > cursor) slices += noData(cursor, start - cursor)
        if (start >= stats.to || start < cursor) continue
        val duration = min(nominalStep, stats.to - start).coerceAtLeast(1)
        val observed = point.observedSeconds.coerceIn(0, duration)
        val online = point.onlineSeconds.coerceIn(0, observed)
        val state = when {
            observed == 0L -> TimelineState.NO_DATA
            online == 0L -> TimelineState.OFFLINE
            online == observed -> TimelineState.ONLINE
            else -> TimelineState.MIXED
        }
        slices += TimelineSlice(
            at = start,
            durationSeconds = duration,
            state = state,
            coverageFraction = observed.toFloat() / duration,
            onlineFraction = if (observed == 0L) 0f else online.toFloat() / observed,
        )
        cursor = max(cursor, start + duration)
    }
    if (cursor < stats.to) slices += noData(cursor, stats.to - cursor)
    return slices
}

data class TimelineSummaryFacts(
    val periodSeconds: Long,
    val observedSeconds: Long,
    val onlineSeconds: Long,
    val missingSeconds: Long,
)

fun timelineSummaryFacts(stats: PeerStats): TimelineSummaryFacts {
    val period = (stats.to - stats.from).coerceAtLeast(0)
    val observed = stats.observedSeconds.coerceIn(0, period)
    return TimelineSummaryFacts(
        periodSeconds = period,
        observedSeconds = observed,
        onlineSeconds = stats.onlineSeconds.coerceIn(0, observed),
        missingSeconds = (period - observed).coerceAtLeast(0),
    )
}

@Composable
private fun timelineSemanticSummary(stats: PeerStats): String {
    val facts = timelineSummaryFacts(stats)
    val period = historyPeriodLabel(facts.periodSeconds)
    if (facts.observedSeconds <= 0) return stringResource(R.string.history_semantic_no_data, period)
    val online = durationLabel(facts.onlineSeconds)
    return if (facts.missingSeconds == 0L) {
        stringResource(R.string.history_semantic_complete, period, online)
    } else {
        stringResource(R.string.history_semantic_missing, period, online, durationLabel(facts.missingSeconds))
    }
}

@Composable
fun ObservedTimeline(stats: PeerStats, modifier: Modifier = Modifier) {
    val online = MaterialTheme.colorScheme.tertiary
    val offline = MaterialTheme.colorScheme.outline
    val missing = MaterialTheme.colorScheme.surfaceVariant
    val hatching = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val mixed = MaterialTheme.colorScheme.secondary
    val summary = timelineSemanticSummary(stats)
    val historyContentDescription = stringResource(R.string.ui_observedtimeline_45451fbf75)
    val slices = buildTimelineSlices(stats)
    val range = (stats.to - stats.from).coerceAtLeast(1)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = historyContentDescription
                stateDescription = summary
            },
    ) {
        drawRect(missing)
        for (slice in slices) {
            val x = size.width * ((slice.at - stats.from).toFloat() / range)
            val width = size.width * (slice.durationSeconds.toFloat() / range)
            if (slice.state == TimelineState.NO_DATA || width <= 0f) continue
            val observedWidth = width * slice.coverageFraction
            val base = if (slice.state == TimelineState.MIXED) mixed else offline
            drawRect(base, Offset(x, 0f), Size(observedWidth, size.height))
            if (slice.onlineFraction > 0f) {
                drawRect(online, Offset(x, 0f), Size(observedWidth * slice.onlineFraction, size.height))
            }
        }
        drawGapHatching(slices, stats.from, range, hatching)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGapHatching(
    slices: List<TimelineSlice>,
    from: Long,
    range: Long,
    color: Color,
) {
    slices.forEach { slice ->
        val left = size.width * ((slice.at - from).toFloat() / range)
        val width = size.width * (slice.durationSeconds.toFloat() / range)
        val covered = slice.coverageFraction.coerceIn(0f, 1f)
        val gapLeft = left + width * covered
        val gapWidth = width * (1f - covered)
        if (gapWidth <= 0f) return@forEach
        clipRect(left = gapLeft, right = gapLeft + gapWidth) {
            var x = gapLeft - size.height
            while (x < gapLeft + gapWidth) {
                drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                x += 8.dp.toPx()
            }
        }
    }
}

private fun noData(at: Long, duration: Long) = TimelineSlice(
    at = at,
    durationSeconds = duration.coerceAtLeast(0),
    state = TimelineState.NO_DATA,
    coverageFraction = 0f,
    onlineFraction = 0f,
)
