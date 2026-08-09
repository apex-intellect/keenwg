package ru.anisimov.keenwg.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ImportOrigin { CLIPBOARD, QR, FILE }

enum class Protocol { VLESS, VMESS, TROJAN, HYSTERIA2, WIREGUARD, AMNEZIAWG }

@Serializable
enum class SourceKind {
    @SerialName("subscription") SUBSCRIPTION,
    @SerialName("share_link") SHARE_LINK,
    @SerialName("config") CONFIG,
}

data class ImportPreview(
    val origin: ImportOrigin,
    val sourceKind: SourceKind,
    val protocol: Protocol?,
    val host: String,
    val port: Int,
    val transport: String,
    val security: String,
    val serverName: String? = null,
    val warnings: List<String> = emptyList(),
)

enum class ImportErrorCode {
    TOO_LARGE,
    EMPTY,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_FIELD,
    INVALID_ENDPOINT,
    INVALID_ENCODING,
    INVALID_CREDENTIAL,
}

class ImportException(val code: ImportErrorCode) : Exception("Connection import failed: ${code.name.lowercase()}")
