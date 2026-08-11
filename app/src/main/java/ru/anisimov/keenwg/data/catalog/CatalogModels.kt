package ru.anisimov.keenwg.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("state_version") val stateVersion: ULong,
    val groups: List<CatalogGroup>,
    val sources: List<CatalogSource>,
    val nodes: List<CatalogNode>,
)

@Serializable data class CatalogGroup(val id: String, val label: String, val order: Int)

@Serializable
data class CatalogSource(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val kind: CatalogSourceKind,
    val label: String,
    @SerialName("adapter_id") val adapterId: String,
    val status: SourceStatus,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("last_refresh") val lastRefresh: String? = null,
    val warnings: List<String>,
    val foreign: Boolean,
    @SerialName("adapter_state_version") val adapterStateVersion: ULong = 0u,
)

@Serializable
data class CatalogNode(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("display_name") val displayName: String,
    val country: String = "",
    val protocol: CatalogProtocol,
    val host: String,
    val port: Int,
    val transport: String = "",
    val security: String = "",
    @SerialName("server_name") val serverName: String = "",
    val alpn: String = "",
    val flow: String = "",
    val active: Boolean,
    val testable: Boolean,
    val activatable: Boolean,
    val warnings: List<String>,
)

@Serializable enum class CatalogSourceKind {
    @SerialName("subscription") SUBSCRIPTION,
    @SerialName("share_link") SHARE_LINK,
    @SerialName("config") CONFIG,
    @SerialName("foreign") FOREIGN,
}

@Serializable enum class SourceStatus {
    @SerialName("ready") READY,
    @SerialName("stale") STALE,
    @SerialName("error") ERROR,
}

@Serializable enum class CatalogProtocol {
    @SerialName("vless") VLESS,
    @SerialName("vmess") VMESS,
    @SerialName("trojan") TROJAN,
    @SerialName("hysteria2") HYSTERIA2,
    @SerialName("wireguard") WIREGUARD,
    @SerialName("amneziawg") AMNEZIAWG,
}

data class CatalogSourceDraft(
    val groupId: String,
    val kind: SourceKind,
    val label: String,
    val adapterId: String,
)

@Serializable
data class CatalogOperation(
    @SerialName("schema_version") val schemaVersion: Int,
    val result: String,
    val catalog: CatalogDocument? = null,
    val test: CatalogNodeTest? = null,
    val error: String? = null,
)

@Serializable
data class CatalogNodeTest(
    @SerialName("node_id") val nodeId: String,
    val reachable: Boolean,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    val error: String? = null,
    @SerialName("observed_at") val observedAt: String,
)

enum class CatalogErrorCode {
    INVALID_SETTINGS, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, PAYLOAD_TOO_LARGE,
    UNAVAILABLE, UNSUPPORTED_SCHEMA, PROTOCOL,
}

class CatalogException(val code: CatalogErrorCode) : RuntimeException(
    when (code) {
        CatalogErrorCode.INVALID_SETTINGS -> "Некорректные настройки подключения"
        CatalogErrorCode.UNAUTHORIZED -> "Устройство не авторизовано"
        CatalogErrorCode.FORBIDDEN -> "Недостаточно прав"
        CatalogErrorCode.NOT_FOUND -> "Объект не найден"
        CatalogErrorCode.CONFLICT -> "Состояние каталога изменилось"
        CatalogErrorCode.PAYLOAD_TOO_LARGE -> "Источник слишком большой"
        CatalogErrorCode.UNAVAILABLE -> "Каталог подключений недоступен"
        CatalogErrorCode.UNSUPPORTED_SCHEMA -> "Схема каталога не поддерживается"
        CatalogErrorCode.PROTOCOL -> "Некорректный ответ Companion"
    },
)
