package ru.anisimov.keenwg.data.collector

import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.domain.model.PeerStatsPoint
interface StatsGateway {
    suspend fun history(peerIds: List<String>, range: HistoryRange, now: Long): PeerStats
}

class CollectorRepository(private val gateway: CollectorHistoryGateway) : StatsGateway {
    override suspend fun history(peerIds: List<String>, range: HistoryRange, now: Long): PeerStats {
        val histories = peerIds.distinct().map { gateway.history(it, range) }
        val merged = histories.flatMap(CollectorHistory::points)
            .groupBy(CollectorPoint::at)
            .toSortedMap()
            .map { (at, bucket) ->
                val observed = bucket.maxOf(CollectorPoint::observedSeconds).coerceAtLeast(0)
                PeerStatsPoint(
                    at = at,
                    observedSeconds = observed,
                    onlineSeconds = bucket.maxOf(CollectorPoint::onlineSeconds).coerceIn(0, observed),
                    clientUploadBytes = bucket.sumOf(CollectorPoint::clientUploadBytes).coerceAtLeast(0),
                    clientDownloadBytes = bucket.sumOf(CollectorPoint::clientDownloadBytes).coerceAtLeast(0),
                )
            }
        val observed = merged.sumOf(PeerStatsPoint::observedSeconds)
        val online = merged.sumOf(PeerStatsPoint::onlineSeconds).coerceAtMost(observed)
        val effectiveFrom = histories.minOfOrNull(CollectorHistory::from) ?: range.from
        val effectiveTo = histories.maxOfOrNull(CollectorHistory::to) ?: range.to
        val duration = (effectiveTo - effectiveFrom).coerceAtLeast(1)
        return PeerStats(
            from = effectiveFrom,
            to = effectiveTo,
            observedSeconds = observed,
            onlineSeconds = online,
            lastOnlineAt = histories.mapNotNull(CollectorHistory::lastOnlineAt).maxOrNull(),
            clientUploadBytes = merged.sumOf(PeerStatsPoint::clientUploadBytes),
            clientDownloadBytes = merged.sumOf(PeerStatsPoint::clientDownloadBytes),
            counterResets = histories.sumOf(CollectorHistory::counterResets),
            coverageRatio = (observed.toDouble() / duration).coerceIn(0.0, 1.0),
            points = downsample(merged),
        )
    }

    private fun downsample(points: List<PeerStatsPoint>): List<PeerStatsPoint> {
        if (points.size <= MAX_VISUAL_POINTS) return points
        val chunkSize = (points.size + MAX_VISUAL_POINTS - 1) / MAX_VISUAL_POINTS
        return points.chunked(chunkSize).map { chunk ->
            PeerStatsPoint(
                at = chunk.first().at,
                observedSeconds = chunk.sumOf(PeerStatsPoint::observedSeconds),
                onlineSeconds = chunk.sumOf(PeerStatsPoint::onlineSeconds),
                clientUploadBytes = chunk.sumOf(PeerStatsPoint::clientUploadBytes),
                clientDownloadBytes = chunk.sumOf(PeerStatsPoint::clientDownloadBytes),
            )
        }
    }

    private companion object {
        const val MAX_VISUAL_POINTS = 288
    }
}
