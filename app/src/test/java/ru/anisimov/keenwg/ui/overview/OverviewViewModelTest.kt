package ru.anisimov.keenwg.ui.overview

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.capability.CapabilityRegistry
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.PairedDevice
import ru.anisimov.keenwg.data.companion.PairingCredential
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `single profile hides selector and exposes only configured direct modules`() = runTest(dispatcher) {
        val profile = profile(host = "192.168.1.1")
        val harness = harness(RouterProfilesState.Ready(listOf(profile), profile.id), active(profile))

        advanceUntilIdle()

        assertFalse(harness.vm.state.value.showProfileSelector)
        assertEquals("Home", harness.vm.state.value.selectedProfileName)
        assertTrue(harness.vm.state.value.destinations.contains(TopLevelDestination.ACCESS))
        assertFalse(harness.vm.state.value.destinations.contains(TopLevelDestination.CONNECTIONS))
    }

    @Test fun `multiple profiles show selector and selection delegates to profile store`() = runTest(dispatcher) {
        val home = profile(id = "home")
        val office = profile(id = "office")
        var selected: String? = null
        val harness = harness(
            RouterProfilesState.Ready(listOf(home, office), home.id),
            active(home),
            onSelect = { selected = it },
        )

        advanceUntilIdle()
        harness.vm.selectProfile("office").join()

        assertTrue(harness.vm.state.value.showProfileSelector)
        assertEquals("office", selected)
    }

    @Test fun `locked store gates mutations and optional destinations`() = runTest(dispatcher) {
        val harness = harness(RouterProfilesState.Locked("profile_store_locked"), null)

        advanceUntilIdle()

        val state = harness.vm.state.value
        assertFalse(state.mutationsEnabled)
        assertEquals(listOf(TopLevelDestination.OVERVIEW, TopLevelDestination.SYSTEM), state.destinations)
        assertEquals(OverviewHealth.LOCKED, state.health)
    }

    @Test fun `capability refresh preserves independent modules and sanitizes health failure`() = runTest(dispatcher) {
        val profile = profile(companionUrl = "https://router:18779", pin = PIN)
        val companion = QueueCompanion(
            ArrayDeque(listOf(
                CapabilityDocument(capabilities = listOf(capability("connections.xkeen"))),
                IllegalStateException("device-token secret detail"),
            )),
        )
        val harness = harness(
            RouterProfilesState.Ready(listOf(profile), profile.id),
            active(profile, companionToken = "device-token"),
            companion = companion,
        )

        advanceUntilIdle()
        assertTrue(harness.vm.state.value.destinations.contains(TopLevelDestination.CONNECTIONS))
        harness.vm.refresh().join()
        advanceUntilIdle()

        val state = harness.vm.state.value
        assertEquals(OverviewHealth.DEGRADED, state.health)
        assertFalse(state.message.orEmpty().contains("device-token"))
        assertNull(state.activeXkeenNode)
    }

    private fun harness(
        profiles: RouterProfilesState,
        active: ActiveRouterProfile?,
        companion: CompanionClient = QueueCompanion(ArrayDeque()),
        onSelect: suspend (String) -> Unit = {},
    ): Harness {
        val vm = OverviewViewModel(
            profilesFlow = MutableStateFlow(profiles),
            activeProfileFlow = MutableStateFlow(active),
            companion = companion,
            registry = CapabilityRegistry(),
            xkeenNode = { null },
            selectProfile = onSelect,
            dispatcher = dispatcher,
        )
        return Harness(vm)
    }

    private fun active(profile: RouterProfile, companionToken: String = "") =
        ActiveRouterProfile(profile, RouterSecrets(companionToken = companionToken))

    private fun profile(
        id: String = "home",
        host: String = "",
        companionUrl: String = "",
        pin: String = "",
    ) = RouterProfile(
        id = id,
        displayName = if (id == "home") "Home" else "Office",
        host = host,
        rciPort = if (host.isBlank()) 0 else 80,
        interfaceId = "Wireguard0",
        serverPublicKey = "",
        endpoint = "",
        subnetBase = "10.8.0.",
        dns = "192.168.1.1",
        mtu = 1380,
        keepalive = 25,
        companionUrl = companionUrl,
        certificatePin = pin,
        collectorUrl = "",
    )

    private fun capability(id: String) = Capability(
        id = id,
        access = CapabilityAccess.READ,
        available = true,
        transport = "companion",
    )

    private data class Harness(val vm: OverviewViewModel)

    private class QueueCompanion(private val responses: ArrayDeque<Any>) : CompanionClient {
        override suspend fun capabilities(profile: RouterProfile, deviceToken: String): CapabilityDocument {
            val response = responses.removeFirstOrNull() ?: return CapabilityDocument(capabilities = emptyList())
            if (response is Throwable) throw response
            return response as CapabilityDocument
        }
        override suspend fun exchange(profile: RouterProfile, offerId: String, secret: String, label: String): PairingCredential = error("unused")
        override suspend fun devices(profile: RouterProfile, deviceToken: String): List<PairedDevice> = error("unused")
        override suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String) = error("unused")
    }

    private companion object {
        const val PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
