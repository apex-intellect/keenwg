package ru.anisimov.keenwg.data.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class CompanionAssetTest {
    @Test fun `verified arm64 asset preserves manifest and bytes`() {
        val body = "companion-archive".toByteArray()
        val source = FakeAssetSource(manifest(body), body)

        val verified = CompanionAssetVerifier(source).load()

        assertEquals("0.7.0", verified.manifest.version)
        assertTrue(body.contentEquals(verified.bytes))
    }

    @Test fun `modified bytes wrong size architecture and schema fail closed`() {
        val body = "companion-archive".toByteArray()
        val cases = listOf(
            FakeAssetSource(manifest(body), "modified".toByteArray()),
            FakeAssetSource(manifest(body).replace("\"size\":${body.size}", "\"size\":1"), body),
            FakeAssetSource(manifest(body).replace("\"arm64\"", "\"x86_64\""), body),
            FakeAssetSource(manifest(body).replace("\"schema_version\":1", "\"schema_version\":2"), body),
        )

        cases.forEach { source ->
            assertTrue(runCatching { CompanionAssetVerifier(source).load() }.exceptionOrNull() is AssetVerificationException)
        }
    }

    private fun manifest(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        return """{"schema_version":1,"version":"0.7.0","architecture":"arm64","asset":"keenwg-companion-arm64.tgz","sha256":"$digest","size":${body.size}}"""
    }

    private data class FakeAssetSource(val manifest: String, val asset: ByteArray) : CompanionAssetSource {
        override fun manifestBytes() = manifest.toByteArray()
        override fun assetBytes(name: String) = asset.copyOf()
    }
}
