package ru.anisimov.keenwg.data.collector

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CollectorMeta(
    val version: String,
    @SerialName("max_points") val maxPoints: Int,
)

data class HistoryRange(
    val from: Long,
    val to: Long,
    val resolution: String = "auto",
    val limit: Int = 2000,
) {
    init {
        require(from >= 0 && to > from)
        require(resolution in setOf("auto", "raw", "5m", "1h"))
        require(limit in 1..2000)
    }
}

@Serializable
data class CollectorHistory(
    @SerialName("peer_id") val peerId: String,
    val from: Long,
    val to: Long,
    val resolution: String,
    @SerialName("observed_seconds") val observedSeconds: Long,
    @SerialName("online_seconds") val onlineSeconds: Long,
    @SerialName("last_online_at") val lastOnlineAt: Long?,
    @SerialName("client_upload_bytes") val clientUploadBytes: Long,
    @SerialName("client_download_bytes") val clientDownloadBytes: Long,
    @SerialName("counter_resets") val counterResets: Int,
    @SerialName("coverage_ratio") val coverageRatio: Double,
    val points: List<CollectorPoint>,
)

@Serializable
data class CollectorPoint(
    val at: Long,
    @SerialName("observed_seconds") val observedSeconds: Long,
    @SerialName("online_seconds") val onlineSeconds: Long,
    @SerialName("client_upload_bytes") val clientUploadBytes: Long,
    @SerialName("client_download_bytes") val clientDownloadBytes: Long,
)
