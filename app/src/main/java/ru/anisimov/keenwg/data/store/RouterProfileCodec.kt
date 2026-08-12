package ru.anisimov.keenwg.data.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@Serializable
data class RouterProfileIndex(
    @SerialName("schema_version") val schemaVersion: Int = 3,
    @SerialName("selected_profile_id") val selectedProfileId: String,
    val profiles: List<RouterProfile>,
)

@Serializable
private data class RouterSecretDocument(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val secrets: Map<String, RouterSecrets>,
)

object RouterProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeIndex(index: RouterProfileIndex): String {
        validate(index)
        return json.encodeToString(RouterProfileIndex.serializer(), index)
    }

    fun decodeIndex(raw: String): RouterProfileIndex {
        val root = json.parseToJsonElement(raw).jsonObject
        val migrated = when (root.schemaVersion()) {
            1 -> migrateIndexV2(migrateIndexV1(root))
            2 -> migrateIndexV2(root)
            3 -> root
            else -> throw IllegalArgumentException("Unsupported router profile schema")
        }
        return json.decodeFromJsonElement(RouterProfileIndex.serializer(), migrated).also(::validate)
    }

    fun encodeSecrets(secrets: Map<String, RouterSecrets>): String =
        json.encodeToString(RouterSecretDocument.serializer(), RouterSecretDocument(secrets = secrets))

    fun decodeSecrets(raw: String): Map<String, RouterSecrets> {
        val root = json.parseToJsonElement(raw).jsonObject
        val migrated = when (root.schemaVersion()) {
            1 -> migrateSecretsV1(root)
            2 -> root
            else -> throw IllegalArgumentException("Unsupported router secret schema")
        }
        val document = json.decodeFromJsonElement(RouterSecretDocument.serializer(), migrated)
        require(document.schemaVersion == 2) { "Unsupported router secret schema" }
        return document.secrets
    }

    private fun migrateIndexV1(root: JsonObject): JsonObject {
        val profiles = root["profiles"] as? JsonArray ?: error("Router profiles are missing")
        return JsonObject(root.toMutableMap().apply {
            this["schema_version"] = JsonPrimitive(2)
            this["profiles"] = JsonArray(profiles.map { element ->
                JsonObject(element.jsonObject.toMutableMap().apply {
                    remove("legacyXkeenUrl")
                    this["schemaVersion"] = JsonPrimitive(2)
                })
            })
        })
    }

    private fun migrateIndexV2(root: JsonObject): JsonObject {
        val profiles = root["profiles"] as? JsonArray ?: error("Router profiles are missing")
        return JsonObject(root.toMutableMap().apply {
            this["schema_version"] = JsonPrimitive(3)
            this["profiles"] = JsonArray(profiles.map { element ->
                val profile = element.jsonObject
                JsonObject(profile.toMutableMap().apply {
                    this["schemaVersion"] = JsonPrimitive(3)
                    putIfAbsent("sshHost", profile["host"] ?: JsonPrimitive(""))
                    putIfAbsent("sshPort", JsonPrimitive(222))
                    putIfAbsent("sshUsername", JsonPrimitive("root"))
                    putIfAbsent("sshHostKeyAlgorithm", JsonPrimitive(""))
                    putIfAbsent("sshHostKeySha256", JsonPrimitive(""))
                })
            })
        })
    }

    private fun migrateSecretsV1(root: JsonObject): JsonObject {
        val secrets = root["secrets"]?.jsonObject ?: error("Router secrets are missing")
        return JsonObject(root.toMutableMap().apply {
            this["schema_version"] = JsonPrimitive(2)
            this["secrets"] = JsonObject(secrets.mapValues { (_, element) ->
                JsonObject(element.jsonObject.toMutableMap().apply { remove("legacyXkeenToken") })
            })
        })
    }

    private fun JsonObject.schemaVersion(): Int = this["schema_version"]?.jsonPrimitive?.int
        ?: error("Router schema version is missing")

    private fun validate(index: RouterProfileIndex) {
        require(index.schemaVersion == 3) { "Unsupported router profile schema" }
        require(index.profiles.isNotEmpty()) { "At least one router profile is required" }
        require(index.profiles.all {
            it.schemaVersion == 3 && it.id.isNotBlank() && it.displayName.isNotBlank() &&
                (it.sshHost.isBlank() || (it.sshHost.length <= 253 && it.sshHost.none { char ->
                    char.isWhitespace() || char.isISOControl() || char in "/\\@"
                })) &&
                it.sshPort in 1..65535 && it.sshUsername.matches(Regex("[A-Za-z0-9._-]{1,64}")) &&
                ((it.sshHostKeyAlgorithm.isBlank() && it.sshHostKeySha256.isBlank()) ||
                    (it.sshHostKeyAlgorithm.matches(Regex("[A-Za-z0-9@._+-]{1,64}")) &&
                        it.sshHostKeySha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))))
        }) { "Invalid router profile" }
        require(index.profiles.map { it.id }.distinct().size == index.profiles.size) { "Duplicate router profile" }
        require(index.profiles.any { it.id == index.selectedProfileId }) { "Selected router profile is missing" }
    }
}
