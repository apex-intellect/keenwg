package ru.anisimov.keenwg.data.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@Serializable
data class RouterProfileIndex(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("selected_profile_id") val selectedProfileId: String,
    val profiles: List<RouterProfile>,
)

@Serializable
private data class RouterSecretDocument(
    @SerialName("schema_version") val schemaVersion: Int = 1,
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

    fun decodeIndex(raw: String): RouterProfileIndex =
        json.decodeFromString(RouterProfileIndex.serializer(), raw).also(::validate)

    fun encodeSecrets(secrets: Map<String, RouterSecrets>): String =
        json.encodeToString(RouterSecretDocument.serializer(), RouterSecretDocument(secrets = secrets))

    fun decodeSecrets(raw: String): Map<String, RouterSecrets> {
        val document = json.decodeFromString(RouterSecretDocument.serializer(), raw)
        require(document.schemaVersion == 1) { "Unsupported router secret schema" }
        return document.secrets
    }

    private fun validate(index: RouterProfileIndex) {
        require(index.schemaVersion == 1) { "Unsupported router profile schema" }
        require(index.profiles.isNotEmpty()) { "At least one router profile is required" }
        require(index.profiles.all { it.schemaVersion == 1 && it.id.isNotBlank() && it.displayName.isNotBlank() }) { "Invalid router profile" }
        require(index.profiles.map { it.id }.distinct().size == index.profiles.size) { "Duplicate router profile" }
        require(index.profiles.any { it.id == index.selectedProfileId }) { "Selected router profile is missing" }
    }
}
