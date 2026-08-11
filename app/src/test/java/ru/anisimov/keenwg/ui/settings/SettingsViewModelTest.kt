package ru.anisimov.keenwg.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.collector.CollectorMeta
import ru.anisimov.keenwg.domain.model.ServerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `save and test is one sequential guarded operation`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val store = FakeSettingsStore()
        val rci = object : SettingsRciGateway {
            var authCount = 0
            override suspend fun authenticate(settings: ServerSettings) { authCount++; release.await() }
            override suspend fun get(settings: ServerSettings, path: String) = "{}"
        }
        val vm = SettingsViewModel(store, rci)
        val valid = ServerSettings(
            password = "secret",
            serverPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            endpoint = "vpn.example.net:51820",
        )

        vm.saveAndTest(valid)
        runCurrent()
        vm.saveAndTest(valid)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, rci.authCount)
        assertEquals(listOf(valid), store.saved)
    }

    @Test fun `collector probe uses dedicated settings and does not save`() = runTest(dispatcher) {
        val store = FakeSettingsStore()
        val probed = mutableListOf<ServerSettings>()
        val collector = SettingsCollectorGateway { settings ->
            probed += settings
            CollectorMeta("1.0.0", 2_000)
        }
        val draft = ServerSettings(
            collectorUrl = "http://10.8.0.1:18777",
            collectorToken = "collector-secret",
        )
        val vm = SettingsViewModel(store = store, collector = collector)
        val message = async { vm.msg.first() }
        runCurrent()

        vm.testCollector(draft)
        advanceUntilIdle()

        assertEquals(listOf(draft), probed)
        assertEquals(emptyList<ServerSettings>(), store.saved)
        assertEquals("Сборщик 1.0.0 доступен, токен принят", message.await())
    }
}

private class FakeSettingsStore : SettingsStoreGateway {
    override val settings = MutableStateFlow(ServerSettings())
    val saved = mutableListOf<ServerSettings>()
    override suspend fun save(settings: ServerSettings) { saved += settings }
}
