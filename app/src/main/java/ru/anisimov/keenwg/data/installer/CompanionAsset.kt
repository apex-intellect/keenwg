package ru.anisimov.keenwg.data.installer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class CompanionAssetManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    val version: String,
    val architecture: String,
    val asset: String,
    val sha256: String,
    val size: Int,
)

data class VerifiedCompanionAsset(
    val manifest: CompanionAssetManifest,
    val bytes: ByteArray,
)

interface CompanionAssetSource {
    fun manifestBytes(): ByteArray
    fun assetBytes(name: String): ByteArray
}

class AndroidCompanionAssetSource(context: Context) : CompanionAssetSource {
    private val assets = context.applicationContext.assets

    override fun manifestBytes() = assets.open("companion/manifest.json").use { it.readBounded(MAX_MANIFEST_BYTES) }

    override fun assetBytes(name: String): ByteArray {
        require(name == EXPECTED_ASSET_NAME) { "Unexpected companion asset" }
        return assets.open("companion/$name").use { it.readBounded(MAX_ASSET_BYTES) }
    }
}

class CompanionAssetVerifier(
    private val source: CompanionAssetSource,
) {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(): VerifiedCompanionAsset {
        val manifest = try {
            json.decodeFromString<CompanionAssetManifest>(source.manifestBytes().toString(Charsets.UTF_8))
        } catch (failure: SerializationException) {
            throw AssetVerificationException(failure)
        } catch (failure: IllegalArgumentException) {
            throw AssetVerificationException(failure)
        }
        if (manifest.schemaVersion != 1 || manifest.architecture != "arm64" ||
            manifest.asset != EXPECTED_ASSET_NAME || !manifest.version.matches(VERSION) ||
            manifest.size !in 1..MAX_ASSET_BYTES || !manifest.sha256.matches(SHA256)
        ) {
            throw AssetVerificationException()
        }
        val bytes = try {
            source.assetBytes(manifest.asset)
        } catch (failure: Exception) {
            throw AssetVerificationException(failure)
        }
        if (bytes.size != manifest.size) throw AssetVerificationException()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!MessageDigest.isEqual(digest.toByteArray(), manifest.sha256.toByteArray())) {
            bytes.fill(0)
            throw AssetVerificationException()
        }
        return VerifiedCompanionAsset(manifest, bytes)
    }

    private companion object {
        val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

class AssetVerificationException(cause: Throwable? = null) : Exception("Bundled companion asset verification failed", cause)

private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw AssetVerificationException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val EXPECTED_ASSET_NAME = "keenwg-companion-arm64.tgz"
private const val MAX_MANIFEST_BYTES = 16 * 1024
internal const val MAX_ASSET_BYTES = 16 * 1024 * 1024
