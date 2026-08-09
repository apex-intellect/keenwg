package ru.anisimov.keenwg.data.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings
import java.time.Duration

class CollectorException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface CollectorHistoryGateway {
    suspend fun history(settings: ServerSettings, peerId: String, range: HistoryRange): CollectorHistory
}

fun interface CollectorProbeGateway {
    suspend fun probe(settings: ServerSettings): CollectorMeta
}

class CollectorClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(5))
        .build(),
    private val urlValidator: (String) -> String? = ServerSettingsValidator::validateCollectorUrl,
) : CollectorHistoryGateway, CollectorProbeGateway {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun history(settings: ServerSettings, peerId: String, range: HistoryRange): CollectorHistory = withContext(Dispatchers.IO) {
        require(Regex("^[0-9a-f]{64}$").matches(peerId)) { "Некорректный идентификатор peer" }
        val urlIssue = urlValidator(settings.collectorUrl)
        if (settings.collectorUrl.isBlank() || urlIssue != null) throw CollectorException(urlIssue ?: "Сборщик истории не настроен")
        if (settings.collectorToken.isBlank()) throw CollectorException("Укажите токен сборщика истории")
        val url = settings.collectorUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/peers")
            .addPathSegment(peerId)
            .addPathSegment("history")
            .addQueryParameter("from", range.from.toString())
            .addQueryParameter("to", range.to.toString())
            .addQueryParameter("resolution", range.resolution)
            .addQueryParameter("limit", range.limit.toString())
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${settings.collectorToken}")
            .header("Cache-Control", "no-store")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw CollectorException("Сборщик отклонил токен")
                    404 -> return@withContext emptyHistory(peerId, range)
                }
                if (!response.isSuccessful) throw CollectorException("История недоступна (HTTP ${response.code})")
                val body = response.body ?: throw CollectorException("Сборщик вернул пустой ответ")
                val source = body.source()
                source.request(MAX_BODY_BYTES + 1L)
                if (source.buffer.size > MAX_BODY_BYTES) throw CollectorException("Ответ сборщика слишком большой")
                val text = source.readUtf8()
                return@withContext runCatching { json.decodeFromString<CollectorHistory>(text) }
                    .getOrElse { throw CollectorException("Схема ответа сборщика не поддерживается", it) }
            }
        } catch (known: CollectorException) {
            throw known
        } catch (failure: Exception) {
            throw CollectorException("История временно недоступна", failure)
        }
    }

    override suspend fun probe(settings: ServerSettings): CollectorMeta = withContext(Dispatchers.IO) {
        validateConnectionSettings(settings)
        val request = Request.Builder()
            .url(settings.collectorUrl.toHttpUrl().newBuilder().addPathSegments("v1/meta").build())
            .header("Authorization", "Bearer ${settings.collectorToken}")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw CollectorException("Сборщик отклонил токен")
                if (!response.isSuccessful) throw CollectorException("Сборщик недоступен (HTTP ${response.code})")
                val text = readBounded(response)
                val meta = runCatching { json.decodeFromString<CollectorMeta>(text) }
                    .getOrElse { throw CollectorException("Схема ответа сборщика не поддерживается", it) }
                if (meta.version.isBlank() || meta.maxPoints !in 1..2000) {
                    throw CollectorException("Схема ответа сборщика не поддерживается")
                }
                meta
            }
        } catch (known: CollectorException) {
            throw known
        } catch (failure: Exception) {
            throw CollectorException("Сборщик временно недоступен", failure)
        }
    }

    private fun emptyHistory(peerId: String, range: HistoryRange) = CollectorHistory(
        peerId, range.from, range.to, range.resolution, 0, 0, null, 0, 0, 0, 0.0, emptyList(),
    )

    private fun validateConnectionSettings(settings: ServerSettings) {
        val urlIssue = urlValidator(settings.collectorUrl)
        if (settings.collectorUrl.isBlank() || urlIssue != null) throw CollectorException(urlIssue ?: "Сборщик истории не настроен")
        if (settings.collectorToken.isBlank()) throw CollectorException("Укажите токен сборщика истории")
    }

    private fun readBounded(response: okhttp3.Response): String {
        val body = response.body ?: throw CollectorException("Сборщик вернул пустой ответ")
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1L)
        if (source.buffer.size > MAX_BODY_BYTES) throw CollectorException("Ответ сборщика слишком большой")
        return source.readUtf8()
    }

    private companion object { const val MAX_BODY_BYTES = 1_048_576L }
}
