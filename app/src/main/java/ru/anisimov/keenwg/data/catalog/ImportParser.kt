package ru.anisimov.keenwg.data.catalog

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object ImportParser {
    private const val MAX_BYTES = 1_048_576
    private val json = Json { ignoreUnknownKeys = false }

    fun preview(bytes: ByteArray, origin: ImportOrigin): ImportPreview {
        try {
            if (bytes.size > MAX_BYTES) throw ImportException(ImportErrorCode.TOO_LARGE)
            val text = decodeUtf8(bytes).trim()
            if (text.isEmpty()) throw ImportException(ImportErrorCode.EMPTY)
            return when {
                text.startsWith("vless://", ignoreCase = true) -> parseStandardLink(text, origin, Protocol.VLESS)
                text.startsWith("trojan://", ignoreCase = true) -> parseStandardLink(text, origin, Protocol.TROJAN)
                text.startsWith("hysteria2://", ignoreCase = true) || text.startsWith("hy2://", ignoreCase = true) ->
                    parseStandardLink(text, origin, Protocol.HYSTERIA2)
                text.startsWith("vmess://", ignoreCase = true) -> parseVmess(text, origin)
                text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) ->
                    parseSubscription(text, origin)
                text.lineSequence().any { it.trim().equals("[Interface]", ignoreCase = true) } -> parseConf(text, origin)
                else -> throw ImportException(ImportErrorCode.UNSUPPORTED_FORMAT)
            }
        } catch (failure: ImportException) {
            throw failure
        } catch (_: Exception) {
            throw ImportException(ImportErrorCode.INVALID_ENCODING)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseStandardLink(text: String, origin: ImportOrigin, protocol: Protocol): ImportPreview {
        val uri = runCatching { URI(text) }.getOrElse { throw ImportException(ImportErrorCode.INVALID_ENDPOINT) }
        if (uri.rawUserInfo.isNullOrBlank()) throw ImportException(ImportErrorCode.INVALID_CREDENTIAL)
        val host = normalizeHost(uri.host ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT))
        val port = validPort(uri.port)
        val query = parseQuery(uri.rawQuery)
        val allowed = when (protocol) {
            Protocol.VLESS -> VLESS_FIELDS
            Protocol.TROJAN -> TROJAN_FIELDS
            Protocol.HYSTERIA2 -> HYSTERIA_FIELDS
            else -> emptySet()
        }
        rejectUnknown(query, allowed)
        val transport = when (protocol) {
            Protocol.HYSTERIA2 -> "udp"
            else -> query["type"]?.ifBlank { "tcp" } ?: "tcp"
        }
        val security = when (protocol) {
            Protocol.HYSTERIA2, Protocol.TROJAN -> query["security"]?.ifBlank { "tls" } ?: "tls"
            else -> query["security"]?.ifBlank { "none" } ?: "none"
        }
        val serverName = query["sni"]?.takeIf(String::isNotBlank)?.let(::normalizeHost)
        val warnings = buildList {
            if (query["insecure"] == "1" || query["allowInsecure"] == "1") add("tls_verification_disabled")
            if (security == "reality" && query["pbk"].isNullOrBlank()) add("reality_public_key_missing")
        }
        return ImportPreview(origin, SourceKind.SHARE_LINK, protocol, host, port, transport, security, serverName, warnings)
    }

    private fun parseVmess(text: String, origin: ImportOrigin): ImportPreview {
        val encoded = text.substringAfter("://").substringBefore('#')
        val decoded = decodeBase64(encoded)
        try {
            val objectValue = runCatching { json.parseToJsonElement(decodeUtf8(decoded)) as JsonObject }
                .getOrElse { throw ImportException(ImportErrorCode.INVALID_ENCODING) }
            rejectUnknown(objectValue.keys.associateWith { "" }, VMESS_FIELDS)
            val credential = objectValue.string("id")
            if (credential.isBlank()) throw ImportException(ImportErrorCode.INVALID_CREDENTIAL)
            val host = normalizeHost(objectValue.string("add"))
            val port = validPort(objectValue.string("port").toIntOrNull() ?: -1)
            val transport = objectValue.optionalString("net")?.ifBlank { "tcp" } ?: "tcp"
            val security = objectValue.optionalString("tls")?.ifBlank { "none" } ?: "none"
            val serverName = objectValue.optionalString("sni")?.takeIf(String::isNotBlank)?.let(::normalizeHost)
            return ImportPreview(origin, SourceKind.SHARE_LINK, Protocol.VMESS, host, port, transport, security, serverName)
        } finally {
            decoded.fill(0)
        }
    }

    private fun parseSubscription(text: String, origin: ImportOrigin): ImportPreview {
        val uri = runCatching { URI(text) }.getOrElse { throw ImportException(ImportErrorCode.INVALID_ENDPOINT) }
        if (uri.scheme.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        }
        val host = normalizeHost(uri.host ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT))
        val port = when (uri.port) {
            -1 -> if (uri.scheme.equals("https", true)) 443 else 80
            else -> validPort(uri.port)
        }
        return ImportPreview(
            origin = origin,
            sourceKind = SourceKind.SUBSCRIPTION,
            protocol = null,
            host = host,
            port = port,
            transport = "https",
            security = if (uri.scheme.equals("https", true)) "tls" else "none",
            warnings = if (uri.scheme.equals("http", true)) listOf("unencrypted_subscription") else emptyList(),
        )
    }

    private fun parseConf(text: String, origin: ImportOrigin): ImportPreview {
        var section = ""
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).lowercase()
                if (section !in setOf("interface", "peer")) throw ImportException(ImportErrorCode.UNSUPPORTED_FIELD)
                return@forEach
            }
            val split = line.indexOf('=')
            if (split <= 0 || section.isBlank()) throw ImportException(ImportErrorCode.INVALID_ENCODING)
            val key = line.substring(0, split).trim()
            val value = line.substring(split + 1).trim()
            val normalized = "$section.${key.lowercase()}"
            if (normalized !in CONF_FIELDS || values.put(normalized, value) != null) {
                throw ImportException(ImportErrorCode.UNSUPPORTED_FIELD)
            }
        }
        if (values["interface.privatekey"].isNullOrBlank() || values["peer.publickey"].isNullOrBlank()) {
            throw ImportException(ImportErrorCode.INVALID_CREDENTIAL)
        }
        val endpoint = values["peer.endpoint"] ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        val (host, port) = parseEndpoint(endpoint)
        val isAmnezia = AMNEZIA_FIELDS.any(values::containsKey)
        return ImportPreview(
            origin = origin,
            sourceKind = SourceKind.CONFIG,
            protocol = if (isAmnezia) Protocol.AMNEZIAWG else Protocol.WIREGUARD,
            host = host,
            port = port,
            transport = "udp",
            security = if (isAmnezia) "amneziawg" else "wireguard",
        )
    }

    private fun parseEndpoint(value: String): Pair<String, Int> {
        val match = IPV6_ENDPOINT.matchEntire(value) ?: HOST_ENDPOINT.matchEntire(value)
            ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        val host = match.groups[1]?.value ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        val port = match.groups[2]?.value?.toIntOrNull() ?: throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        return normalizeHost(host) to validPort(port)
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            val keyRaw = part.substringBefore('=', "")
            if (keyRaw.isBlank()) throw ImportException(ImportErrorCode.UNSUPPORTED_FIELD)
            val key = percentDecode(keyRaw)
            val value = percentDecode(part.substringAfter('=', ""))
            if (result.put(key, value) != null) throw ImportException(ImportErrorCode.UNSUPPORTED_FIELD)
        }
        return result
    }

    private fun rejectUnknown(fields: Map<String, String>, allowed: Set<String>) {
        if (fields.keys.any { it !in allowed }) throw ImportException(ImportErrorCode.UNSUPPORTED_FIELD)
    }

    private fun percentDecode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrElse { throw ImportException(ImportErrorCode.INVALID_ENCODING) }

    private fun decodeBase64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return runCatching { Base64.getUrlDecoder().decode(padded) }
            .recoverCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw ImportException(ImportErrorCode.INVALID_ENCODING) }
    }

    private fun decodeUtf8(bytes: ByteArray): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { throw ImportException(ImportErrorCode.INVALID_ENCODING) }

    private fun normalizeHost(value: String): String {
        val raw = value.trim().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (raw.isBlank() || raw.contains(Regex("[\\s/@?#]"))) throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        if (raw.contains(':')) {
            if (!IPV6.matches(raw)) throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
            return raw.lowercase()
        }
        val ascii = runCatching { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase() }
            .getOrElse { throw ImportException(ImportErrorCode.INVALID_ENDPOINT) }
        if (ascii.isBlank() || ascii.length > 253 || (!ascii.contains('.') && ascii != "localhost")) {
            throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        }
        return ascii
    }

    private fun validPort(value: Int): Int {
        if (value !in 1..65535) throw ImportException(ImportErrorCode.INVALID_ENDPOINT)
        return value
    }

    private fun JsonObject.string(key: String): String = optionalString(key)
        ?: throw ImportException(ImportErrorCode.INVALID_ENCODING)

    private fun JsonObject.optionalString(key: String): String? = get(key)?.jsonPrimitive?.content

    private val VLESS_FIELDS = setOf("type", "security", "sni", "fp", "flow", "pbk", "sid", "path", "host", "serviceName", "mode", "alpn", "encryption", "headerType", "allowInsecure")
    private val TROJAN_FIELDS = setOf("type", "security", "sni", "fp", "path", "host", "serviceName", "mode", "alpn", "allowInsecure")
    private val HYSTERIA_FIELDS = setOf("sni", "insecure", "obfs", "obfs-password", "pinSHA256")
    private val VMESS_FIELDS = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp")
    private val CONF_FIELDS = setOf(
        "interface.privatekey", "interface.address", "interface.dns", "interface.mtu", "interface.listenport",
        "interface.jc", "interface.jmin", "interface.jmax", "interface.s1", "interface.s2",
        "interface.h1", "interface.h2", "interface.h3", "interface.h4",
        "peer.publickey", "peer.presharedkey", "peer.endpoint", "peer.allowedips", "peer.persistentkeepalive",
    )
    private val AMNEZIA_FIELDS = setOf("interface.jc", "interface.jmin", "interface.jmax", "interface.s1", "interface.s2", "interface.h1", "interface.h2", "interface.h3", "interface.h4")
    private val IPV6_ENDPOINT = Regex("^\\[([0-9A-Fa-f:]+)]:(\\d{1,5})$")
    private val HOST_ENDPOINT = Regex("^([^:\\s]+):(\\d{1,5})$")
    private val IPV6 = Regex("^[0-9A-Fa-f:]+$")
}
