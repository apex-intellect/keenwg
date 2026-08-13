package ru.anisimov.keenwg.data.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException

enum class HistoryFailure {
    PROTECTED_ACCESS_REQUIRED,
    UPDATE_COMPONENT,
    RECONNECT,
    UNAVAILABLE,
    UNSUPPORTED_RESPONSE,
}

class HistoryException(
    val reason: HistoryFailure,
    cause: Throwable? = null,
) : Exception("WireGuard history unavailable", cause)

interface CollectorHistoryGateway {
    suspend fun history(peerId: String, range: HistoryRange): CollectorHistory
}

class CompanionHistoryClient(
    private val endpointProvider: suspend () -> CompanionEndpoint?,
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
) : CollectorHistoryGateway {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    override suspend fun history(peerId: String, range: HistoryRange): CollectorHistory = withContext(Dispatchers.IO) {
        requireQuery(peerId, range)
        val endpoint = try {
            endpointProvider()
        } catch (failure: Exception) {
            throw HistoryException(HistoryFailure.UNAVAILABLE, failure)
        } ?: throw HistoryException(HistoryFailure.PROTECTED_ACCESS_REQUIRED)
        val body = json.encodeToString(HistoryQuery(1, peerId, range.from, range.to, range.resolution, range.limit))
        val response = try {
            transport.execute(
                endpoint = endpoint,
                path = HISTORY_PATH,
                method = "POST",
                body = body,
                maxResponseBytes = MAX_RESPONSE_BYTES,
            )
        } catch (failure: CompanionResponseTooLargeException) {
            throw HistoryException(HistoryFailure.UNSUPPORTED_RESPONSE, failure)
        } catch (failure: CompanionTransportException) {
            throw HistoryException(HistoryFailure.UNAVAILABLE, failure)
        }
        when (response.status) {
            in 200..299 -> Unit
            401, 403 -> throw HistoryException(HistoryFailure.RECONNECT)
            404 -> throw HistoryException(HistoryFailure.UPDATE_COMPONENT)
            400, 413, 422 -> throw HistoryException(HistoryFailure.UNSUPPORTED_RESPONSE)
            else -> throw HistoryException(HistoryFailure.UNAVAILABLE)
        }
        val document = try {
            json.decodeFromString<HistoryDocument>(response.body)
        } catch (failure: Exception) {
            throw HistoryException(HistoryFailure.UNSUPPORTED_RESPONSE, failure)
        }
        if (document.schemaVersion != 1 || !validHistory(document.history, peerId, range)) {
            throw HistoryException(HistoryFailure.UNSUPPORTED_RESPONSE)
        }
        document.history
    }

    private fun requireQuery(peerId: String, range: HistoryRange) {
        val bucket = bucketSeconds(range.resolution)
        if (!PEER_ID.matches(peerId) || range.resolution !in RESOLUTIONS ||
            range.to - range.from > MAX_RANGE_SECONDS || bucket > 0 && range.to > Long.MAX_VALUE - (bucket - 1)
        ) {
            throw HistoryException(HistoryFailure.UNSUPPORTED_RESPONSE)
        }
    }

    private fun validHistory(history: CollectorHistory, peerId: String, range: HistoryRange): Boolean {
        val (expectedFrom, expectedTo) = expectedWindow(range)
        if (history.peerId != peerId || history.from != expectedFrom || history.to != expectedTo ||
            history.resolution != range.resolution || history.observedSeconds < 0 || history.onlineSeconds !in 0..history.observedSeconds ||
            history.clientUploadBytes < 0 || history.clientDownloadBytes < 0 || history.counterResets < 0 ||
            !history.coverageRatio.isFinite() || history.coverageRatio !in 0.0..1.0 || history.points.size > range.limit ||
            history.lastOnlineAt?.let { it !in history.from until history.to } == true
        ) return false
        var previous = -1L
        return history.points.all { point ->
            val valid = point.at in history.from until history.to && point.at > previous &&
                point.observedSeconds >= 0 && point.onlineSeconds in 0..point.observedSeconds &&
                point.clientUploadBytes >= 0 && point.clientDownloadBytes >= 0
            previous = point.at
            valid
        }
    }

    private fun expectedWindow(range: HistoryRange): Pair<Long, Long> {
        val bucket = bucketSeconds(range.resolution)
        if (bucket == 0L) return range.from to range.to
        return (range.from / bucket) * bucket to ((range.to + bucket - 1) / bucket) * bucket
    }

    private fun bucketSeconds(resolution: String): Long = when (resolution) {
        "5m" -> 5L * 60
        "1h" -> 60L * 60
        else -> 0
    }

    @Serializable
    private data class HistoryQuery(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("peer_id") val peerId: String,
        val from: Long,
        val to: Long,
        val resolution: String,
        val limit: Int,
    )

    @Serializable
    private data class HistoryDocument(
        @SerialName("schema_version") val schemaVersion: Int,
        val history: CollectorHistory,
    )

    private companion object {
        const val HISTORY_PATH = "/v1/access/wireguard/history/query"
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_RANGE_SECONDS = 400L * 24 * 60 * 60
        val PEER_ID = Regex("^[0-9a-f]{64}$")
        val RESOLUTIONS = setOf("raw", "5m", "1h")
    }
}
