package ru.anisimov.keenwg.domain.model

data class PeerStats(
    val from: Long,
    val to: Long,
    val observedSeconds: Long,
    val onlineSeconds: Long,
    val lastOnlineAt: Long?,
    val clientUploadBytes: Long,
    val clientDownloadBytes: Long,
    val counterResets: Int,
    val coverageRatio: Double,
    val points: List<PeerStatsPoint>,
)

data class PeerStatsPoint(
    val at: Long,
    val observedSeconds: Long,
    val onlineSeconds: Long,
    val clientUploadBytes: Long,
    val clientDownloadBytes: Long,
)
