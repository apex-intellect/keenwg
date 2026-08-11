package ru.anisimov.keenwg.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.data.collector.CollectorClient
import ru.anisimov.keenwg.data.collector.CollectorRepository
import ru.anisimov.keenwg.data.collector.StatsGateway
import ru.anisimov.keenwg.data.store.KeystoreCipher
import ru.anisimov.keenwg.data.store.PeerConfStore
import ru.anisimov.keenwg.data.store.PeerLineageStore
import ru.anisimov.keenwg.data.store.PeerAccessPolicyStore
import ru.anisimov.keenwg.data.store.LineageStore
import ru.anisimov.keenwg.data.store.SettingsStore
import ru.anisimov.keenwg.data.store.RouterProfileStore
import ru.anisimov.keenwg.data.store.XkeenPreferenceStore
import ru.anisimov.keenwg.data.xkeen.XkeenClient
import ru.anisimov.keenwg.data.xkeen.XkeenRepository
import ru.anisimov.keenwg.data.network.NetworkRepository
import ru.anisimov.keenwg.data.network.NetworkExclusionClient
import ru.anisimov.keenwg.data.network.DomainRoutingClient
import ru.anisimov.keenwg.data.capability.CapabilityRegistry
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.HttpCompanionClient
import ru.anisimov.keenwg.data.installer.AndroidCompanionAssetSource
import ru.anisimov.keenwg.data.installer.CompanionAssetVerifier
import ru.anisimov.keenwg.data.installer.InstallerCoordinator
import ru.anisimov.keenwg.data.installer.JschSshTransport
import ru.anisimov.keenwg.data.installer.RouterProfileInstallerGateway
import ru.anisimov.keenwg.data.catalog.CatalogClient
import ru.anisimov.keenwg.data.catalog.CatalogGateway
import ru.anisimov.keenwg.data.catalog.ImportDraftStore
import ru.anisimov.keenwg.data.catalog.SharedPreferencesImportDraftPersistence
import ru.anisimov.keenwg.data.routes.RouteExplainClient
import ru.anisimov.keenwg.data.routes.RouteExplainGateway
import ru.anisimov.keenwg.data.routes.ScenarioClient
import ru.anisimov.keenwg.data.routes.ScenarioGateway
import ru.anisimov.keenwg.data.support.SupportClient
import ru.anisimov.keenwg.data.support.SupportGateway
import ru.anisimov.keenwg.data.backup.BackupClient
import ru.anisimov.keenwg.data.backup.BackupGateway

/** Minimal manual DI — initialised once from MainActivity. */
@SuppressLint("StaticFieldLeak") // Stores receive applicationContext only; no Activity is retained.
object ServiceLocator {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var routerProfileStore: RouterProfileStore
        private set
    lateinit var repository: PeerRepository
        private set
    lateinit var statsGateway: StatsGateway
        private set
    lateinit var lineageStore: LineageStore
        private set
    lateinit var xkeenRepository: XkeenRepository
        private set
    lateinit var xkeenPreferenceStore: XkeenPreferenceStore
        private set
    lateinit var networkRepository: NetworkRepository
        private set
    lateinit var networkExclusionClient: NetworkExclusionClient
        private set
    lateinit var domainRoutingClient: DomainRoutingClient
        private set
    lateinit var companionClient: CompanionClient
        private set
    lateinit var capabilityRegistry: CapabilityRegistry
        private set
    lateinit var installerCoordinator: InstallerCoordinator
        private set
    lateinit var catalogGateway: CatalogGateway
        private set
    lateinit var importDraftStore: ImportDraftStore
        private set
    lateinit var routeExplainGateway: RouteExplainGateway
        private set
    lateinit var scenarioGateway: ScenarioGateway
        private set
    lateinit var supportGateway: SupportGateway
        private set
    lateinit var backupGateway: BackupGateway
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        val cipher = KeystoreCipher()
        val app = context.applicationContext
        settingsStore = SettingsStore(app, cipher)
        routerProfileStore = settingsStore.routerProfiles
        applicationScope.launch { runCatching { settingsStore.initialize() } }
        lineageStore = PeerLineageStore(app)
        repository = PeerRepository(RciClient(), PeerConfStore(app, cipher), lineageStore, accessPolicyStore = PeerAccessPolicyStore(app))
        statsGateway = CollectorRepository(CollectorClient())
        val companionTransport = CompanionHttpTransport()
        xkeenRepository = XkeenRepository(XkeenClient(companionTransport))
        xkeenPreferenceStore = XkeenPreferenceStore(app)
        networkRepository = NetworkRepository()
        networkExclusionClient = NetworkExclusionClient(companionTransport)
        domainRoutingClient = DomainRoutingClient(companionTransport)
        companionClient = HttpCompanionClient(companionTransport)
        capabilityRegistry = CapabilityRegistry()
        catalogGateway = CatalogClient()
        routeExplainGateway = RouteExplainClient()
        scenarioGateway = ScenarioClient()
        supportGateway = SupportClient()
        backupGateway = BackupClient()
        importDraftStore = ImportDraftStore(
            SharedPreferencesImportDraftPersistence(app),
            KeystoreCipher("keenwg.import-draft.v1"),
        )
        installerCoordinator = InstallerCoordinator(
            assets = CompanionAssetVerifier(AndroidCompanionAssetSource(app)),
            ssh = JschSshTransport(),
            companion = companionClient,
            profiles = RouterProfileInstallerGateway(routerProfileStore),
        )
    }
}
