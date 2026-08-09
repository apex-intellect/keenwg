package ru.anisimov.keenwg.data.installer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class JschSshTransportContractTest {
    @Test fun `password bytes are zeroed after successful authentication`() = runTest {
        val password = "temporary-secret".toByteArray()
        val expected = observation()
        val fake = FakeSession()
        val transport = JschSshTransport(
            observer = HostKeyObserver { _, _ -> expected },
            connector = SshSessionConnector { _, received, pin, _ ->
                assertTrue(received.contentEquals("temporary-secret".toByteArray()))
                assertEquals(expected, pin)
                fake
            },
        )

        assertEquals(fake, transport.connect(endpoint(), password, expected))
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `password bytes are zeroed when authentication throws`() = runTest {
        val password = "temporary-secret".toByteArray()
        val transport = JschSshTransport(
            observer = HostKeyObserver { _, _ -> observation() },
            connector = SshSessionConnector { _, _, _, _ -> error("auth failed") },
        )

        assertTrue(runCatching { transport.connect(endpoint(), password, observation()) }.isFailure)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `temporary paths and command nonce are strict`() {
        val nonce = "0123456789abcdef0123456789abcdef"
        assertEquals("/opt/tmp/keenwg-$nonce.tar.gz", ValidatedTemporaryPath.archive(nonce).value)
        assertEquals("/opt/tmp/keenwg-$nonce.json", ValidatedTemporaryPath.request(nonce).value)
        listOf("../x", "/opt/tmp/x", "/opt/tmp/keenwg-${nonce.uppercase()}.json", "/opt/tmp/keenwg-$nonce.json;reboot").forEach { path ->
            assertTrue(runCatching { ValidatedTemporaryPath.parse(path) }.isFailure)
        }
        assertTrue(runCatching { FixedCommand.install("../bad") }.isFailure)
        assertEquals(
            "umask 077; work=/opt/tmp/keenwg-$nonce; mkdir -m 700 \"\$work\"; tar -xzf /opt/tmp/keenwg-$nonce.tar.gz -C \"\$work\"; \"\$work/install-companion.sh\" --request /opt/tmp/keenwg-$nonce.json",
            FixedCommand.install(nonce).render(),
        )
    }

    @Test fun `stdout and stderr collectors never retain more than 64 KiB`() {
        val output = BoundedByteCollector(65_536)
        output.write(ByteArray(70_000) { 'x'.code.toByte() })

        assertEquals(65_536, output.bytes().size)
        assertTrue(output.truncated)
    }

    @Test fun `installer transport source contains no insecure SSH escape hatches`() {
        val directory = Path.of("src/main/java/ru/anisimov/keenwg/data/installer")
        val source = Files.walk(directory).use { files ->
            val combined = StringBuilder()
            files.filter { file: Path -> Files.isRegularFile(file) }.forEach { file: Path ->
                combined.append(String(Files.readAllBytes(file))).append('\n')
            }
            combined.toString()
        }
        listOf("StrictHostKeyChecking=no", "UserKnownHostsFile=/dev/null", "sh -c").forEach {
            assertFalse("Forbidden SSH option: $it", source.contains(it, ignoreCase = true))
        }
        assertFalse(source.contains("setPassword(endpoint.username"))
        assertFalse(source.contains("setPassword(endpoint.password"))
    }

    private fun endpoint() = SshEndpoint("192.168.1.1", 222, "root")
    private fun observation() = HostKeyObservation("ssh-ed25519", "SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg")

    private class FakeSession : SshSession {
        override suspend fun exec(command: FixedCommand) = CommandResult(0, "", "")
        override suspend fun upload(bytes: ByteArray, remotePath: ValidatedTemporaryPath) = Unit
        override fun close() = Unit
    }
}
