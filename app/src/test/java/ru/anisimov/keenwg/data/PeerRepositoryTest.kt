package ru.anisimov.keenwg.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.crypto.WgKeyPair
import ru.anisimov.keenwg.data.crypto.WgKeys
import ru.anisimov.keenwg.data.collector.PeerId
import ru.anisimov.keenwg.data.rci.ConfiguredPeer
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.data.store.ConfStore
import ru.anisimov.keenwg.data.store.LineageStore
import ru.anisimov.keenwg.domain.PeerInputException
import ru.anisimov.keenwg.domain.SettingsValidationException
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.data.store.AccessPolicyStore

class PeerRepositoryTest {
    private val keyPair = WgKeys.generate()
    private val serverKey = WgKeys.generate().publicKey

    @Test fun `invalid server settings never mutate router`() = runTest {
        val router = StatefulFakeRouter(existingPeer())
        val repo = repository(router)

        expectThrows<SettingsValidationException> { repo.add(validSettings().copy(endpoint = ""), "phone", "10.8.0.7") }

        assertTrue(router.postedBodies.isEmpty())
    }

    @Test fun `explicit occupied ip is rejected before key generation`() = runTest {
        var generated = 0
        val repo = repository(StatefulFakeRouter(existingPeer(ip = "10.8.0.7"))) { generated++; keyPair }

        expectThrows<PeerInputException> { repo.add(validSettings(), "phone", "10.8.0.7") }

        assertEquals(0, generated)
    }

    @Test fun `add returns single reveal config only after two successful read backs`() = runTest {
        val router = StatefulFakeRouter(existingPeer())
        val confStore = FakeConfStore()
        val result = repository(router, confStore = confStore).add(validSettings(), "phone", "10.8.0.7")

        assertEquals("10.8.0.7", result.peer.ip)
        assertEquals(keyPair.publicKey, result.peer.publicKey)
        assertEquals(2, router.successfulReadBacksFor(keyPair.publicKey))
        assertEquals(1, result.reveal.remainingReveals)
        assertNull(confStore.get(keyPair.publicKey))
    }

    @Test fun `issuance validates and records client networks dns expiry and history policy`() = runTest {
        val router = StatefulFakeRouter(existingPeer())
        val policies = FakeAccessPolicyStore()
        val policy = AccessPolicy(
            allowedNetworks = listOf("10.0.0.0/8"),
            dnsServers = listOf("1.1.1.1"),
            expiresAtEpochSeconds = 2_000,
            historyEnabled = false,
        )
        val result = repository(router, policies = policies, now = 1_000).add(validSettings(), "phone", "10.8.0.7", policy)

        assertTrue(result.conf.contains("AllowedIPs = 10.0.0.0/8"))
        assertTrue(result.conf.contains("DNS = 1.1.1.1"))
        assertEquals(policy, policies.get(result.peer.publicKey))

        val invalid = policy.copy(expiresAtEpochSeconds = 1_000)
        val before = router.postedBodies.size
        expectThrows<IllegalArgumentException> { repository(router, now = 1_000).add(validSettings(), "other", "10.8.0.8", invalid) }
        assertEquals(before, router.postedBodies.size)
    }

    @Test fun `failed add is removed and staged config deleted after verified rollback`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old, Boundary.FinalVerify)
        val store = FakeConfStore()

        val error = expectThrows<RouterMutationError.RolledBack> {
            repository(router, confStore = store).add(validSettings(), "phone", "10.8.0.7")
        }

        assertEquals("Новый доступ не применён. Настройки роутера восстановлены.", error.message)
        assertEquals(setOf(old.publicKey), router.configuredKeys())
        assertNull(store.get(keyPair.publicKey))
    }

    @Test fun `failed rollback keeps config and returns uncertain state`() = runTest {
        val store = FakeConfStore()
        val router = StatefulFakeRouter(existingPeer(), Boundary.RollbackVerify)

        expectThrows<RouterMutationError.Uncertain> {
            repository(router, confStore = store).add(validSettings(), "phone", "10.8.0.7")
        }

        assertNotNull(store.get(keyPair.publicKey))
    }

    @Test fun `add create post failure proves absence and removes staged config`() = runTest {
        val store = FakeConfStore()
        val router = StatefulFakeRouter(existingPeer(), Boundary.CreatePost)
        expectThrows<RouterMutationError.RolledBack> {
            repository(router, confStore = store).add(validSettings(), "phone", "10.8.0.7")
        }
        assertNull(store.get(keyPair.publicKey))
    }

    @Test fun `add first verification and save failures roll back`() = runTest {
        for (boundary in listOf(Boundary.FirstVerify, Boundary.Save)) {
            val old = existingPeer()
            val router = StatefulFakeRouter(old, boundary)
            expectThrows<RouterMutationError.RolledBack> {
                repository(router).add(validSettings(), "phone", "10.8.0.7")
            }
            assertEquals(setOf(old.publicKey), router.configuredKeys())
        }
    }

    @Test fun `rotation creates disabled candidate before cutting over`() = runTest {
        val old = existingPeer(enabled = true)
        val router = StatefulFakeRouter(old)
        repository(router).regenerate(validSettings(), old.publicKey)

        val phaseOne = router.snapshots.first { keyPair.publicKey in it.keys }
        assertTrue(old.publicKey in phaseOne.keys)
        assertFalse(phaseOne.getValue(keyPair.publicKey).enabled)
        assertNull(phaseOne.getValue(keyPair.publicKey).allowIp)
    }

    @Test fun `rotation success preserves fields and records lineage`() = runTest {
        val old = existingPeer(name = "anna-phone", ip = "10.8.0.5", keepalive = 25, enabled = true)
        val router = StatefulFakeRouter(old)
        val confStore = FakeConfStore().apply { put(old.publicKey, "old-conf") }
        val lineage = FakeLineageStore()

        val result = repository(router, confStore, lineage = lineage).regenerate(validSettings(), old.publicKey)
        val current = router.peer(result.peer.publicKey)!!

        assertEquals(listOf(old.name, old.allowIp, old.keepalive, old.enabled), listOf(current.name, current.allowIp, current.keepalive, current.enabled))
        assertEquals(
            listOf(PeerId.compute("Wireguard0", old.publicKey), PeerId.compute("Wireguard0", result.peer.publicKey)),
            lineage.idsFor(result.peer.publicKey),
        )
        assertNull(confStore.get(old.publicKey))
        assertNull(confStore.get(result.peer.publicKey))
    }

    @Test fun `rotation preserves managed policy and revoke removes it`() = runTest {
        val old = existingPeer()
        val policy = AccessPolicy(listOf("192.168.0.0/16"), listOf("192.168.1.1"), 2_000, false)
        val policies = FakeAccessPolicyStore().apply { put(old.publicKey, policy) }
        val repo = repository(StatefulFakeRouter(old), policies = policies, now = 1_000)

        val rotated = repo.regenerate(validSettings(), old.publicKey)
        assertNull(policies.get(old.publicKey))
        assertEquals(policy, policies.get(rotated.peer.publicKey))

        repo.remove(validSettings(), rotated.peer.publicKey)
        assertNull(policies.get(rotated.peer.publicKey))
    }

    @Test fun `router committed rotation publishes new peer to detail cache before local finalization`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old)
        val failingLineage = object : LineageStore {
            override suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String) {
                throw IllegalStateException("local store failed")
            }
            override suspend fun idsFor(publicKey: String) = emptyList<String>()
            override suspend fun remove(publicKey: String) = Unit
        }
        val repo = repository(router, lineage = failingLineage)
        repo.list(validSettings())

        val error = expectThrows<RouterMutationError.LocalFinalization> {
            repo.regenerate(validSettings(), old.publicKey)
        }

        assertEquals(keyPair.publicKey, error.newPublicKey)
        assertEquals(listOf(keyPair.publicKey), repo.cachedPeers.value.map { it.publicKey })
    }

    @Test fun `cutover verification failure restores old peer and old conf`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old, Boundary.CutoverVerify)
        val store = FakeConfStore().apply { put(old.publicKey, "old-conf") }

        expectThrows<RouterMutationError.RolledBack> {
            repository(router, confStore = store).regenerate(validSettings(), old.publicKey)
        }

        assertEquals(old, router.peer(old.publicKey))
        assertNotNull(store.get(old.publicKey))
        assertNull(store.get(keyPair.publicKey))
    }

    @Test fun `rotation rollback verification failure keeps both configs`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old, Boundary.RotationRollbackVerify)
        val store = FakeConfStore().apply { put(old.publicKey, "old-conf") }

        expectThrows<RouterMutationError.Uncertain> {
            repository(router, confStore = store).regenerate(validSettings(), old.publicKey)
        }

        assertNotNull(store.get(old.publicKey))
        assertNotNull(store.get(keyPair.publicKey))
    }

    @Test fun `rotation failures at every router boundary restore old peer`() = runTest {
        val boundaries = listOf(
            Boundary.CreatePost,
            Boundary.FirstVerify,
            Boundary.CutoverVerify,
            Boundary.Save,
            Boundary.FinalVerify,
        )
        for (boundary in boundaries) {
            val old = existingPeer()
            val router = StatefulFakeRouter(old, boundary)
            val store = FakeConfStore().apply { put(old.publicKey, "old-conf") }

            expectThrows<RouterMutationError.RolledBack> {
                repository(router, confStore = store).regenerate(validSettings(), old.publicKey)
            }

            assertEquals("boundary=$boundary", old, router.peer(old.publicKey))
            assertNotNull("boundary=$boundary", store.get(old.publicKey))
            assertNull("boundary=$boundary", store.get(keyPair.publicKey))
        }
    }

    @Test fun `rotation rollback post failure keeps recovery configs`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old, Boundary.RotationRollbackPost)
        val store = FakeConfStore().apply { put(old.publicKey, "old-conf") }

        expectThrows<RouterMutationError.Uncertain> {
            repository(router, confStore = store).regenerate(validSettings(), old.publicKey)
        }

        assertNotNull(store.get(old.publicKey))
        assertNotNull(store.get(keyPair.publicKey))
    }

    @Test fun `rename without allow ip is validated by name and verified`() = runTest {
        val old = existingPeer(ip = null)
        val router = StatefulFakeRouter(old)

        repository(router).rename(validSettings(), old.publicKey, "renamed")

        assertEquals("renamed", router.peer(old.publicKey)?.name)
    }

    @Test fun `rename success requires final readback`() = runTest {
        val old = existingPeer()
        val router = StatefulFakeRouter(old, Boundary.FinalVerify)

        expectThrows<RouterMutationError.RolledBack> {
            repository(router).rename(validSettings(), old.publicKey, "renamed")
        }
        assertEquals(old, router.peer(old.publicKey))
    }

    private fun repository(
        router: StatefulFakeRouter,
        confStore: FakeConfStore = FakeConfStore(),
        lineage: LineageStore = FakeLineageStore(),
        policies: AccessPolicyStore = FakeAccessPolicyStore(),
        now: Long = 1_000,
        generator: () -> WgKeyPair = { keyPair },
    ) = PeerRepository(router, confStore, lineage, KeyGenerator(generator), policies) { now }

    private fun validSettings() = ServerSettings(password = "secret", serverPublicKey = serverKey, endpoint = "vpn.example.net:51820")

    private fun existingPeer(
        name: String = "existing",
        ip: String? = "10.8.0.3",
        keepalive: Int = 25,
        enabled: Boolean = true,
    ): ConfiguredPeer {
        val publicKey = WgKeys.generate().publicKey
        val suffixes = buildList {
            ip?.let { add("allow-ips $it 255.255.255.255") }
            add("keepalive-interval $keepalive")
            add(if (enabled) "connect" else "no connect")
        }
        return ConfiguredPeer(publicKey, name, ip, keepalive, enabled, suffixes)
    }
}

private enum class Boundary { CreatePost, FirstVerify, Save, FinalVerify, RollbackVerify, CutoverVerify, RotationRollbackPost, RotationRollbackVerify }

private class StatefulFakeRouter(
    initial: ConfiguredPeer,
    private val failAt: Boundary? = null,
) : RciClient() {
    private val initialPublicKey = initial.publicKey
    private val peers = linkedMapOf(initial.publicKey to initial)
    val postedBodies = mutableListOf<String>()
    val snapshots = mutableListOf<Map<String, ConfiguredPeer>>()
    private val readBacks = mutableMapOf<String, Int>()
    private var saved = false
    private var failureTriggered = false
    private var rollback = false
    private var rotationPhase = false
    private var cutover = false

    override suspend fun authenticate(s: ServerSettings) = Unit

    override suspend fun get(s: ServerSettings, path: String): String {
        if (!path.contains("running-config")) return showInterface()
        if (failAt == Boundary.FirstVerify && peers.size > 1 && !saved && !failureTriggered) {
            failureTriggered = true
            throw IllegalStateException("first verify failed")
        }
        if (failAt in setOf(Boundary.CutoverVerify, Boundary.RotationRollbackPost, Boundary.RotationRollbackVerify) && cutover && !saved && !failureTriggered) {
            failureTriggered = true
            throw IllegalStateException("cutover verify failed")
        }
        if (failAt in setOf(Boundary.FinalVerify, Boundary.RollbackVerify) && saved && !failureTriggered) {
            failureTriggered = true
            throw IllegalStateException("final verify failed")
        }
        if (failAt in setOf(Boundary.RollbackVerify, Boundary.RotationRollbackVerify) && rollback) throw IllegalStateException("rollback verify failed")
        peers.keys.forEach { readBacks[it] = (readBacks[it] ?: 0) + 1 }
        return runningConfig()
    }

    override suspend fun post(s: ServerSettings, bodyJson: String): String {
        if (failureTriggered) rollback = true
        if (rollback && failAt == Boundary.RotationRollbackPost) throw IllegalStateException("rollback post failed")
        postedBodies += bodyJson
        if (failAt == Boundary.CreatePost && postedBodies.size == 1) {
            failureTriggered = true
            throw IllegalStateException("create post failed")
        }
        Json.parseToJsonElement(bodyJson).jsonArray.forEach { element ->
            val obj = element.jsonObject
            obj["parse"]?.jsonPrimitive?.content?.let(::applyCommand)
            if (obj["system"] != null) saved = true
        }
        if (peers.size > 1 && initialPublicKey in peers) rotationPhase = true
        if (rotationPhase && initialPublicKey !in peers && !rollback) cutover = true
        snapshots += peers.toMap()
        if (failAt == Boundary.Save && saved && !rollback && !failureTriggered) {
            failureTriggered = true
            throw IllegalStateException("save failed")
        }
        return "[]"
    }

    fun configuredKeys() = peers.keys.toSet()
    fun peer(publicKey: String) = peers[publicKey]
    fun successfulReadBacksFor(publicKey: String) = readBacks[publicKey] ?: 0

    private fun applyCommand(command: String) {
        Regex("^interface \\S+ no wireguard peer (\\S+)$").matchEntire(command)?.let { peers.remove(it.groupValues[1]); return }
        val match = Regex("^interface \\S+ wireguard peer (\\S+) (.+)$").matchEntire(command) ?: return
        val key = match.groupValues[1]
        val suffix = match.groupValues[2]
        val current = peers[key] ?: ConfiguredPeer(key, "", null, 0, false, emptyList())
        peers[key] = when {
            suffix.startsWith("!") -> current.copy(name = suffix.drop(1))
            suffix.startsWith("allow-ips ") -> current.copy(allowIp = suffix.split(' ')[1], restoreSuffixes = (current.restoreSuffixes + suffix).distinct())
            suffix.startsWith("keepalive-interval ") -> current.copy(keepalive = suffix.substringAfterLast(' ').toInt(), restoreSuffixes = (current.restoreSuffixes + suffix).distinct())
            suffix == "connect" -> current.copy(enabled = true, restoreSuffixes = (current.restoreSuffixes + suffix).distinct())
            suffix == "no connect" -> current.copy(enabled = false, restoreSuffixes = (current.restoreSuffixes + suffix).distinct())
            else -> current
        }
    }

    private fun runningConfig(): String = buildJsonArray {
        add("interface Wireguard0")
        peers.values.forEach { peer ->
            add("    wireguard peer ${peer.publicKey} !${peer.name}")
            peer.allowIp?.let { add("        allow-ips $it 255.255.255.255") }
            add("        keepalive-interval ${peer.keepalive}")
            add(if (peer.enabled) "        connect" else "        no connect")
            add("    !")
        }
    }.let { "{\"message\":$it}" }

    private fun showInterface(): String = buildJsonArray {
        peers.values.forEach { peer ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("public-key", peer.publicKey)
                put("description", peer.name)
                put("online", false)
                put("enabled", peer.enabled)
            })
        }
    }.let { "{\"wireguard\":{\"peer\":$it}}" }
}

private class FakeConfStore : ConfStore {
    private val map = linkedMapOf<String, String>()
    override suspend fun put(pubkey: String, conf: String) { map[pubkey] = conf }
    override suspend fun get(pubkey: String) = map[pubkey]
    override suspend fun remove(pubkey: String) { map.remove(pubkey) }
}

private class FakeLineageStore : LineageStore {
    private val ids = mutableMapOf<String, List<String>>()
    override suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String) {
        ids[newPublicKey] = (ids[oldPublicKey].orEmpty() + PeerId.compute(interfaceId, oldPublicKey) + PeerId.compute(interfaceId, newPublicKey)).distinct()
        ids.remove(oldPublicKey)
    }
    override suspend fun idsFor(publicKey: String) = ids[publicKey].orEmpty()
    override suspend fun remove(publicKey: String) { ids.remove(publicKey) }
}

private class FakeAccessPolicyStore : AccessPolicyStore {
    private val values = mutableMapOf<String, AccessPolicy>()
    override suspend fun put(publicKey: String, policy: AccessPolicy) { values[publicKey] = policy }
    override suspend fun get(publicKey: String) = values[publicKey]
    override suspend fun rotate(oldPublicKey: String, newPublicKey: String, policy: AccessPolicy) {
        values.remove(oldPublicKey)
        values[newPublicKey] = policy
    }
    override suspend fun remove(publicKey: String) { values.remove(publicKey) }
}

private suspend inline fun <reified T : Throwable> expectThrows(noinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
    }
    throw AssertionError("Expected ${T::class.java.name}")
}
