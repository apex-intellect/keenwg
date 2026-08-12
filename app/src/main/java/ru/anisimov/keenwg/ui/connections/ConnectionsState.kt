package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.data.catalog.CatalogDocument
import ru.anisimov.keenwg.data.catalog.CatalogErrorCode
import ru.anisimov.keenwg.data.catalog.CatalogNode
import ru.anisimov.keenwg.data.catalog.CatalogNodeTest
import ru.anisimov.keenwg.data.catalog.ImportPreview

data class PendingImport(val preview: ImportPreview, val duplicateWarning: Boolean)

data class TestedNode(
    val result: CatalogNodeTest,
    val catalogVersion: ULong,
    val receivedAtMillis: Long,
)

data class ConnectionsUiState(
    val loading: Boolean = true,
    val setupRequired: Boolean = false,
    val loadError: CatalogErrorCode? = null,
    val catalog: CatalogDocument? = null,
    val selectedGroupId: String? = null,
    val pendingImport: PendingImport? = null,
    val tests: Map<String, TestedNode> = emptyMap(),
    val busySources: Set<String> = emptySet(),
    val busyNodes: Set<String> = emptySet(),
    val pendingActivation: CatalogNode? = null,
    val message: String? = null,
)
