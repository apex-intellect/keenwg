package ru.anisimov.keenwg.data.installer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.DeviceScope
import ru.anisimov.keenwg.data.companion.PairedDevice
import ru.anisimov.keenwg.data.companion.PairingCredential
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import java.security.MessageDigest

class InstallerCoordinatorTest {
    @Test fun `happy path probes reviews uploads installs pairs saves and cleans in exact order`() = runTest {
        val fixture = Fixture()
        val probePassword = "probe-secret".toByteArray()
        val preparation = fixture.coordinator.prepare("home", fixture.endpoint, fixture.hostKey, probePassword)

        assertEquals("aarch64", preparation.probe.architecture)
        assertEquals(listOf("connect", "exec:Probe", "close"), fixture.events)
        assertTrue(probePassword.all { it == 0.toByte() })
        fixture.events.clear()
        val installPassword = "install-secret".toByteArray()

        val report = fixture.coordinator.install(preparation, installPassword, "Pixel")

        assertEquals("0.7.0", report.version)
        assertEquals(
            listOf(
                "connect",
                "upload:/opt/tmp/keenwg-${Fixture.NONCE}.tar.gz",
                "upload:/opt/tmp/keenwg-${Fixture.NONCE}.json",
                "exec:Install",
                "exec:CreateOwnerPairingOffer",
                "exchange:offer-1",
                "save:https://192.168.1.1:18779",
                "exec:Cleanup",
                "close",
            ),
            fixture.events,
        )
        assertTrue(installPassword.all { it == 0.toByte() })
        assertTrue(report.cleanupSucceeded)
    }

    @Test fun `preparation rejects unsupported architecture and insufficient space without mutation`() = runTest {
        val fixture = Fixture(probe = probeOutput(architecture = "mips", freeKib = 1))

        val failure = failure { fixture.coordinator.prepare("home", fixture.endpoint, fixture.hostKey, "pw".toByteArray()) }

        assertEquals(InstallPhase.PROBE, failure.phase)
        assertTrue(failure.rollbackVerified)
        assertEquals(listOf("connect", "exec:Probe", "close"), fixture.events)
    }

    @Test fun `failure phases zero password and report rollback truthfully`() = runTest {
        val cases = listOf(
            FailurePoint.UPLOAD to (InstallPhase.UPLOAD to true),
            FailurePoint.INSTALL to (InstallPhase.INSTALL to true),
            FailurePoint.OFFER to (InstallPhase.PAIRING_OFFER to false),
            FailurePoint.EXCHANGE to (InstallPhase.PAIRING_EXCHANGE to false),
            FailurePoint.SAVE to (InstallPhase.SAVE_PROFILE to false),
        )
        for ((point, expected) in cases) {
            val fixture = Fixture(failurePoint = point)
            val preparation = fixture.coordinator.prepare("home", fixture.endpoint, fixture.hostKey, "probe".toByteArray())
            fixture.events.clear()
            val password = "install-secret".toByteArray()

            val failure = failure { fixture.coordinator.install(preparation, password, "Pixel") }

            assertEquals(expected.first, failure.phase)
            assertEquals(expected.second, failure.rollbackVerified)
            assertTrue(password.all { it == 0.toByte() })
            assertTrue(fixture.events.last() == "close")
            assertFalse(failure.safeMessage.contains("install-secret"))
        }
    }

    private suspend fun failure(block: suspend () -> Unit): InstallerException = try {
        block(); error("Expected InstallerException")
    } catch (failure: InstallerException) { failure }

    private class Fixture(
        probe: String = probeOutput(),
        failurePoint: FailurePoint? = null,
    ) {
        val events = mutableListOf<String>()
        val endpoint = SshEndpoint("192.168.1.1", 222, "root")
        val hostKey = HostKeyObservation("ssh-ed25519", "SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg")
        private val asset = "archive".toByteArray()
        private val profileGateway = FakeProfileGateway(events, failurePoint)
        val coordinator = InstallerCoordinator(
            assets = CompanionAssetVerifier(FakeAssetSource(asset)),
            ssh = FakeTransport(events, probe, failurePoint),
            companion = FakeCompanion(events, failurePoint),
            profiles = profileGateway,
            nonce = { NONCE },
        )

        private class FakeAssetSource(private val body: ByteArray) : CompanionAssetSource {
            override fun manifestBytes(): ByteArray {
                val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                return """{"schema_version":1,"version":"0.7.0","architecture":"arm64","asset":"keenwg-companion-arm64.tgz","sha256":"$hash","size":${body.size}}""".toByteArray()
            }
            override fun assetBytes(name: String) = body.copyOf()
        }

        companion object { const val NONCE = "0123456789abcdef0123456789abcdef" }
    }

    private class FakeTransport(
        private val events: MutableList<String>,
        private val probe: String,
        private val failurePoint: FailurePoint?,
    ) : SshTransport {
        override suspend fun observeHostKey(endpoint: SshEndpoint) = error("unused")
        override suspend fun connect(endpoint: SshEndpoint, password: ByteArray, expectedHostKey: HostKeyObservation): SshSession {
            events += "connect"
            password.fill(0)
            return object : SshSession {
                override suspend fun exec(command: FixedCommand): CommandResult {
                    events += "exec:${command.javaClass.simpleName}"
                    return when {
                        command is FixedCommand.Probe -> CommandResult(0, probe, "")
                        command is FixedCommand.Install && failurePoint == FailurePoint.INSTALL -> CommandResult(1, "", "rollback complete")
                        command is FixedCommand.CreateOwnerPairingOffer && failurePoint == FailurePoint.OFFER -> CommandResult(1, "", "offer failed")
                        command is FixedCommand.CreateOwnerPairingOffer -> CommandResult(0, pairingJson(), "")
                        else -> CommandResult(0, "", "")
                    }
                }
                override suspend fun upload(bytes: ByteArray, remotePath: ValidatedTemporaryPath) {
                    events += "upload:${remotePath.value}"
                    if (failurePoint == FailurePoint.UPLOAD) error("upload secret failure")
                }
                override fun close() { events += "close" }
            }
        }
    }

    private class FakeCompanion(
        private val events: MutableList<String>,
        private val failurePoint: FailurePoint?,
    ) : CompanionClient {
        override suspend fun exchange(profile: RouterProfile, offerId: String, secret: String, label: String): PairingCredential {
            events += "exchange:$offerId"
            if (failurePoint == FailurePoint.EXCHANGE) error("pair-secret leaked")
            return PairingCredential(deviceId = "phone-1", scope = DeviceScope.OWNER, token = "device-token")
        }
        override suspend fun capabilities(profile: RouterProfile, deviceToken: String) = CapabilityDocument(capabilities = emptyList())
        override suspend fun devices(profile: RouterProfile, deviceToken: String): List<PairedDevice> = emptyList()
        override suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String) = Unit
    }

    private class FakeProfileGateway(
        private val events: MutableList<String>,
        private val failurePoint: FailurePoint?,
    ) : InstallerProfileGateway {
        private val profile = RouterProfile(
            id = "home", displayName = "Home", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            legacyXkeenUrl = "http://192.168.1.1:18778",
        )
        override suspend fun active(profileId: String) = ActiveRouterProfile(profile, RouterSecrets())
        override suspend fun saveCompanion(profileId: String, baseUrl: String, certificatePin: String, deviceToken: String, deviceId: String) {
            events += "save:$baseUrl"
            if (failurePoint == FailurePoint.SAVE) error("device-token leaked")
        }
    }

    private enum class FailurePoint { UPLOAD, INSTALL, OFFER, EXCHANGE, SAVE }
}

private fun probeOutput(architecture: String = "aarch64", freeKib: Long = 100_000) = """
architecture=$architecture
firmware=4.3.1
opt_free_kib=$freeKib
entware=present
legacy_config=present
legacy_running=yes
xkeen=2.4.1
asc=present
xray=present
companion=missing
""".trimIndent() + "\n"

private fun pairingJson() = """{"base_url":"https://192.168.1.1:18779","certificate_pin":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","offer_id":"offer-1","secret":"pair-secret","expires_at":"2026-08-09T00:00:00Z"}"""
