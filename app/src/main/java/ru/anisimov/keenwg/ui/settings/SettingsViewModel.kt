package ru.anisimov.keenwg.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.discovery.DiscoveryPreview
import ru.anisimov.keenwg.data.collector.CollectorClient
import ru.anisimov.keenwg.data.collector.CollectorMeta
import ru.anisimov.keenwg.data.discovery.RouterDiscovery
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.data.xkeen.XkeenStatus
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings

interface SettingsStoreGateway {
    val settings: Flow<ServerSettings>
    suspend fun save(settings: ServerSettings)
}

interface MigrationReviewGateway {
    val pending: Flow<Boolean>
    suspend fun dismiss()
}

interface SettingsRciGateway {
    suspend fun authenticate(settings: ServerSettings)
    suspend fun get(settings: ServerSettings, path: String): String
}

fun interface SettingsCollectorGateway {
    suspend fun probe(settings: ServerSettings): CollectorMeta
}

fun interface SettingsXkeenGateway {
    suspend fun probe(settings: ServerSettings): XkeenStatus
}

class SettingsViewModel(
    private val store: SettingsStoreGateway = serviceSettingsStore(),
    private val rci: SettingsRciGateway = serviceSettingsRci(),
    private val collector: SettingsCollectorGateway = SettingsCollectorGateway { CollectorClient().probe(it) },
    private val xkeen: SettingsXkeenGateway = SettingsXkeenGateway { ServiceLocator.xkeenRepository.probe(it) },
    private val migration: MigrationReviewGateway? = null,
) : ViewModel() {
    constructor() : this(
        store = serviceSettingsStore(),
        rci = serviceSettingsRci(),
        collector = SettingsCollectorGateway { CollectorClient().probe(it) },
        xkeen = SettingsXkeenGateway { ServiceLocator.xkeenRepository.probe(it) },
        migration = serviceMigrationReview(),
    )

    val settings: StateFlow<ServerSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerSettings())
    val migrationReviewPending: StateFlow<Boolean> = (migration?.pending ?: MutableStateFlow(false))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val operationMutex = Mutex()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()
    val preview = MutableStateFlow<DiscoveryPreview?>(null)

    fun saveAndTest(draft: ServerSettings) = guarded {
        ServerSettingsValidator.requireForSave(draft)
        rci.authenticate(draft)
        rci.get(draft, "show/version")
        store.save(draft)
        _msg.emit("Настройки проверены и сохранены ✓")
    }

    fun discover(draft: ServerSettings) = guarded {
        require(draft.host.isNotBlank() && draft.port in 1..65535 && draft.login.isNotBlank()) { "Проверьте адрес, порт и логин роутера" }
        rci.authenticate(draft)
        preview.value = RouterDiscovery.discover(rci.get(draft, "show/interface"), draft)
        _msg.emit("Проверьте найденные параметры перед сохранением")
    }

    fun applyPreviewAndSave(draft: ServerSettings, found: DiscoveryPreview, acceptEndpointCandidate: Boolean) = guarded {
        val reviewed = found.applyTo(draft, acceptEndpointCandidate)
        ServerSettingsValidator.requireForSave(reviewed)
        rci.authenticate(reviewed)
        rci.get(reviewed, "show/version")
        store.save(reviewed)
        preview.value = null
        _msg.emit("Настройки проверены и сохранены ✓")
    }

    fun testCollector(draft: ServerSettings) = guarded {
        ServerSettingsValidator.validateCollectorUrl(draft.collectorUrl)?.let { error(it) }
        val meta = collector.probe(draft)
        _msg.emit("Сборщик ${meta.version} доступен, токен принят")
    }

    fun testXkeenController(draft: ServerSettings) = guarded {
        require(draft.xkeenControllerUrl.isNotBlank()) { "Укажите адрес контроллера XKeen" }
        ServerSettingsValidator.validateXkeenControllerUrl(draft.xkeenControllerUrl)?.let { error(it) }
        require(draft.xkeenControllerToken.isNotBlank()) { "Укажите токен контроллера XKeen" }
        val status = xkeen.probe(draft)
        _msg.emit("Контроллер XKeen ${status.version} доступен, токен принят")
    }

    fun save(draft: ServerSettings) = saveAndTest(draft)
    fun testConnection(draft: ServerSettings) = saveAndTest(draft)
    fun rediscover(draft: ServerSettings) = discover(draft)

    fun dismissMigrationReview() {
        val gateway = migration ?: return
        viewModelScope.launch { gateway.dismiss() }
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        if (!operationMutex.tryLock()) return@launch
        try {
            runCatching { block() }.onFailure { error ->
                _msg.emit(error.message ?: "Не удалось выполнить операцию")
            }
        } finally {
            operationMutex.unlock()
        }
    }
}

private fun serviceSettingsStore(): SettingsStoreGateway = object : SettingsStoreGateway {
    override val settings get() = ServiceLocator.settingsStore.settings
    override suspend fun save(settings: ServerSettings) = ServiceLocator.settingsStore.save(settings)
}

private fun serviceSettingsRci(): SettingsRciGateway = object : SettingsRciGateway {
    private val client = RciClient()
    override suspend fun authenticate(settings: ServerSettings) = client.authenticate(settings)
    override suspend fun get(settings: ServerSettings, path: String) = client.get(settings, path)
}

private fun serviceMigrationReview(): MigrationReviewGateway = object : MigrationReviewGateway {
    override val pending get() = ServiceLocator.routerProfileStore.migrationReviewPending
    override suspend fun dismiss() = ServiceLocator.routerProfileStore.dismissMigrationReview()
}
