package ru.anisimov.keenwg.ui.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.network.NetworkDevice
import ru.anisimov.keenwg.data.network.NetworkGateway
import ru.anisimov.keenwg.data.network.DomainRoutingGateway
import ru.anisimov.keenwg.data.network.DomainRoutingResult
import ru.anisimov.keenwg.data.network.DomainRoutingStatus
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.DomainRuleDraft
import ru.anisimov.keenwg.data.routes.RouteDecision
import ru.anisimov.keenwg.data.routes.RouteExplanation
import ru.anisimov.keenwg.data.routes.RouteExplainGateway
import ru.anisimov.keenwg.data.routes.RouteExplainRequest
import ru.anisimov.keenwg.data.routes.ScenarioApplyResult
import ru.anisimov.keenwg.data.routes.ScenarioCatalog
import ru.anisimov.keenwg.data.routes.ScenarioGateway
import ru.anisimov.keenwg.data.routes.ScenarioModules
import ru.anisimov.keenwg.data.routes.ScenarioPlan
import ru.anisimov.keenwg.data.routes.ScenarioPreset
import ru.anisimov.keenwg.data.routes.ScenarioReview
import ru.anisimov.keenwg.data.routes.ScenarioStep
import ru.anisimov.keenwg.data.routes.RecoveryState
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `load and confirmed static ip refresh the device`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val vm = NetworkViewModel(flowOf(ServerSettings()), gateway)
        advanceUntilIdle()
        assertEquals("xbox", vm.state.value.devices.single().name)

        vm.requestStaticEdit(vm.state.value.devices.single())
        vm.confirmStaticIp("192.168.1.141")
        advanceUntilIdle()

        assertEquals("192.168.1.141", vm.state.value.devices.single().reservedIp)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `segments expose domains even when device loading fails`() = runTest(dispatcher) {
        val domains = FakeDomains()
        val vm = NetworkViewModel(
            flowOf(ServerSettings()), FailingGateway(), domainGateway = domains,
            activeProfileFlow = flowOf(activeCompanionProfile()),
        )
        advanceUntilIdle()

        assertEquals(NetworkSegment.DEVICES, vm.state.value.selectedSegment)
        assertNotNull(vm.state.value.deviceErrorResource)
        assertEquals("okko.sport", vm.state.value.domains!!.rules.single().value)
        vm.selectSegment(NetworkSegment.DOMAINS)
        assertEquals(NetworkSegment.DOMAINS, vm.state.value.selectedSegment)
    }

    @Test fun `uncertain domain mutation blocks writes until successful refresh`() = runTest(dispatcher) {
        val domains = FakeDomains(result = "uncertain")
        val vm = NetworkViewModel(
            flowOf(ServerSettings()), FakeGateway(), domainGateway = domains,
            activeProfileFlow = flowOf(activeCompanionProfile()),
        )
        advanceUntilIdle()
        vm.openDomainCreate()
        vm.updateDomainDraft(DomainRuleDraft("domain", "example.com", "direct", "Example", true))
        vm.reviewDomainDraft()
        vm.confirmDomainMutation()
        advanceUntilIdle()

        assertTrue(vm.state.value.writesBlocked)
        assertNull(vm.state.value.domainEditor)
        val calls = domains.mutations
        vm.openDomainCreate()
        vm.confirmDomainMutation()
        advanceUntilIdle()
        assertEquals(calls, domains.mutations)

        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.writesBlocked)
    }

    @Test fun `protected domain rule cannot open editor`() = runTest(dispatcher) {
        val protected = domainRule().copy(isProtected = true, source = "system")
        val domains = FakeDomains(status = DomainRoutingStatus(1, 11u, listOf(protected), emptyList(), emptyList()))
        val vm = NetworkViewModel(
            flowOf(ServerSettings()), FakeGateway(), domainGateway = domains,
            activeProfileFlow = flowOf(activeCompanionProfile()),
        )
        advanceUntilIdle()
        vm.openDomainEdit(protected)
        assertNull(vm.state.value.domainEditor)
    }

    @Test fun `route explanation is explicit and never mutates routes`() = runTest(dispatcher) {
        val routes = FakeRoutes()
        val active = ActiveRouterProfile(
            RouterProfile(id = "router", displayName = "Router", host = "192.0.2.1", rciPort = 80,
                interfaceId = "Wireguard0", serverPublicKey = "key", endpoint = "vpn.example:51820",
                subnetBase = "10.8.0.", dns = "192.0.2.1", mtu = 1380, keepalive = 25,
                companionUrl = "https://192.0.2.1:18779", certificatePin = "sha256/test"),
            RouterSecrets(companionToken = "device-token"),
        )
        val vm = NetworkViewModel(flowOf(ServerSettings()), FakeGateway(), activeProfileFlow = flowOf(active), routeGateway = routes)
        advanceUntilIdle()

        vm.explainRoute("video.example", "udp", 443, "phone")
        advanceUntilIdle()

        assertEquals("direct", vm.state.value.routeExplanation!!.decision.outcome)
        assertEquals("video.example", routes.request!!.domain)
        assertEquals(1, routes.calls)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `scenario review never applies and confirmation uses exact plan`() = runTest(dispatcher) {
        val scenarios = FakeScenarios()
        val active = activeCompanionProfile()
        val vm = NetworkViewModel(flowOf(ServerSettings()), FakeGateway(), activeProfileFlow = flowOf(active), scenarioGateway = scenarios)
        advanceUntilIdle()
        vm.selectSegment(NetworkSegment.DOMAINS)
        advanceUntilIdle()
        vm.reviewScenario("russia-direct")
        advanceUntilIdle()
        assertEquals(1, scenarios.reviews)
        assertEquals(0, scenarios.applies)
        assertEquals("0123456789abcdef0123456789abcdef", vm.state.value.scenarioReview!!.planId)
        vm.applyReviewedScenario()
        advanceUntilIdle()
        assertEquals(1, scenarios.applies)
        assertEquals("committed", vm.state.value.scenarioResult!!.status)
        assertNull(vm.state.value.scenarioReview)
    }

    @Test fun `pending recovery blocks scenarios until exact confirmed rollback`() = runTest(dispatcher) {
        val scenarios = FakeScenarios(recovery = RecoveryState(1, true, "scenario-apply-0009", listOf("routes")))
        val vm = NetworkViewModel(flowOf(ServerSettings()), FakeGateway(), activeProfileFlow = flowOf(activeCompanionProfile()), scenarioGateway = scenarios)
        advanceUntilIdle(); vm.selectSegment(NetworkSegment.DOMAINS); advanceUntilIdle()
        assertTrue(vm.state.value.writesBlocked)
        assertEquals("scenario-apply-0009", vm.state.value.recoveryState!!.planId)
        vm.reviewScenario("russia-direct"); advanceUntilIdle(); assertEquals(0, scenarios.reviews)
        vm.confirmRecovery(); advanceUntilIdle()
        assertEquals(1, scenarios.recoveries)
        assertFalse(vm.state.value.writesBlocked)
        assertFalse(vm.state.value.recoveryState!!.pending)
    }

    @Test fun `scenario refresh does not clear an unrelated domain write block`() = runTest(dispatcher) {
        val domains = FakeDomains(result = "uncertain")
        val scenarios = FakeScenarios()
        val vm = NetworkViewModel(
            flowOf(ServerSettings()), FakeGateway(),
            domainGateway = domains,
            activeProfileFlow = flowOf(activeCompanionProfile()),
            scenarioGateway = scenarios,
        )
        advanceUntilIdle()
        vm.openDomainCreate()
        vm.updateDomainDraft(DomainRuleDraft("domain", "example.com", "direct", "Example", true))
        vm.reviewDomainDraft()
        vm.confirmDomainMutation()
        advanceUntilIdle()
        assertTrue(vm.state.value.writesBlocked)

        vm.selectSegment(NetworkSegment.DOMAINS)
        advanceUntilIdle()

        assertFalse(vm.state.value.recoveryState!!.pending)
        assertTrue(vm.state.value.writesBlocked)
    }

    @Test fun `sites load ready rules and visible refresh retries both sources`() = runTest(dispatcher) {
        val scenarios = FakeScenarios()
        val vm = NetworkViewModel(
            flowOf(ServerSettings()), FakeGateway(),
            domainGateway = FakeDomains(),
            activeProfileFlow = flowOf(activeCompanionProfile()),
            scenarioGateway = scenarios,
        )
        advanceUntilIdle()

        vm.selectSegment(NetworkSegment.DOMAINS)
        advanceUntilIdle()
        assertEquals(1, scenarios.catalogs)

        vm.refreshVisible()
        advanceUntilIdle()
        assertEquals(2, scenarios.catalogs)
        assertNotNull(vm.state.value.domains)
    }

    private class FakeGateway : NetworkGateway {
        private var reserved: String? = null
        override suspend fun load(settings: ServerSettings) = listOf(NetworkDevice("4c:3b:df:a6:1e:24", "xbox", "XBOX", "192.168.1.141", reserved, false, reserved != null, "Home", null))
        override suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String) { reserved = ip }
        override suspend fun removeStaticReservation(settings: ServerSettings, mac: String) { reserved = null }
    }

    private class FailingGateway : NetworkGateway {
        override suspend fun load(settings: ServerSettings): List<NetworkDevice> = error("Устройства недоступны")
        override suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String) = Unit
        override suspend fun removeStaticReservation(settings: ServerSettings, mac: String) = Unit
    }

    private class FakeDomains(
        var result: String = "committed",
        var status: DomainRoutingStatus = DomainRoutingStatus(1, 11u, listOf(domainRule()), emptyList(), emptyList()),
    ) : DomainRoutingGateway {
        var mutations = 0
        override suspend fun load(endpoint: CompanionEndpoint) = status
        override suspend fun create(endpoint: CompanionEndpoint, status: DomainRoutingStatus, draft: DomainRuleDraft) = mutate()
        override suspend fun update(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String, draft: DomainRuleDraft) = mutate()
        override suspend fun delete(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String) = mutate()
        private fun mutate(): DomainRoutingResult { mutations++; return DomainRoutingResult(result, status) }
    }

    private class FakeRoutes : RouteExplainGateway {
        var calls = 0
        var request: RouteExplainRequest? = null
        override suspend fun explain(profile: RouterProfile, token: String, request: RouteExplainRequest): RouteExplanation {
            calls++
            this.request = request
            return RouteExplanation(1, RouteDecision("direct", "rule-a", "inferred"), emptyList(), emptyList(), emptyList(), emptyList(), "2026-08-09T04:00:00Z")
        }
    }

    private class FakeScenarios(var recovery: RecoveryState = RecoveryState(1, false, null, emptyList())) : ScenarioGateway {
        var catalogs = 0; var reviews = 0; var applies = 0; var recoveries = 0
        private val preset = ScenarioPreset("russia-direct", "Russia direct", true)
        override suspend fun catalog(profile: RouterProfile, token: String): ScenarioCatalog {
            catalogs++
            return ScenarioCatalog(1, 7u, ScenarioModules(domains = true, ip = true), listOf(preset))
        }
        override suspend fun review(profile: RouterProfile, token: String, presetId: String, stateVersion: ULong): ScenarioReview {
            reviews++
            val outcome = ru.anisimov.keenwg.data.routes.ScenarioOutcome("direct")
            return ScenarioReview(1, "0123456789abcdef0123456789abcdef", ScenarioPlan(1, presetId, stateVersion, outcome, listOf(ScenarioStep("domains", "suffix", "ru", outcome)), emptyList()))
        }
        override suspend fun apply(profile: RouterProfile, token: String, presetId: String, stateVersion: ULong, planId: String): ScenarioApplyResult { applies++; return ScenarioApplyResult("committed", null, "scenario-apply-0001") }
        override suspend fun recovery(profile: RouterProfile, token: String) = recovery
        override suspend fun rollback(profile: RouterProfile, token: String, planId: String): ScenarioApplyResult { recoveries++; recovery = RecoveryState(1, false, null, emptyList()); return ScenarioApplyResult("rolled_back", null, planId) }
    }

    private companion object {
        fun domainRule() = DomainRule("rule-a", "domain", "okko.sport", "direct", "Okko", true, "manual", false)
        fun activeCompanionProfile() = ActiveRouterProfile(
            RouterProfile(id = "router", displayName = "Router", host = "192.0.2.1", rciPort = 80, interfaceId = "Wireguard0", serverPublicKey = "key", endpoint = "vpn.example:51820", subnetBase = "10.8.0.", dns = "192.0.2.1", mtu = 1380, keepalive = 25, companionUrl = "https://192.0.2.1:18779", certificatePin = "sha256/test"),
            RouterSecrets(companionToken = "device-token"),
        )
    }
}
