package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.data.catalog.CatalogDocument
import ru.anisimov.keenwg.data.catalog.CatalogErrorCode
import ru.anisimov.keenwg.data.catalog.CatalogNode
import ru.anisimov.keenwg.data.catalog.CatalogNodeTest
import ru.anisimov.keenwg.data.catalog.ImportPreview
import ru.anisimov.keenwg.data.catalog.SourceConfigurationStatus

data class PendingImport(val preview: ImportPreview, val duplicateWarning: Boolean)

data class TestedNode(
    val result: CatalogNodeTest,
    val catalogVersion: ULong,
    val receivedAtMillis: Long,
)

enum class SourceActionState { CHECKING_CONFIGURATION, REFRESHING, SAVING_LINK, DELETING }

enum class SubscriptionLinkError { INVALID_LINK, PERMISSION_DENIED, UNAVAILABLE, UNSUPPORTED }

data class ConnectionsUiState(
    val loading: Boolean = true,
    val setupRequired: Boolean = false,
    val loadError: CatalogErrorCode? = null,
    val catalog: CatalogDocument? = null,
    val selectedGroupId: String? = null,
    val pendingImport: PendingImport? = null,
    val tests: Map<String, TestedNode> = emptyMap(),
    val sourceConfiguration: Map<String, SourceConfigurationStatus> = emptyMap(),
    val sourceActions: Map<String, SourceActionState> = emptyMap(),
    val busySources: Set<String> = emptySet(),
    val busyNodes: Set<String> = emptySet(),
    val pendingActivation: CatalogNode? = null,
    val editingSubscriptionSourceId: String? = null,
    val subscriptionLinkError: SubscriptionLinkError? = null,
    val messageResource: Int? = null,
    val notice: ConnectionNotice? = null,
)
