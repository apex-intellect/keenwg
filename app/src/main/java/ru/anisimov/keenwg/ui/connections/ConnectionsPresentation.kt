package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.data.catalog.CatalogDocument

data class ConnectionCard(
    val id: String,
    val sourceId: String,
    val title: String,
    val subtitle: String,
    val engine: String,
    val active: Boolean,
    val testable: Boolean,
    val activatable: Boolean,
)

fun connectionCards(document: CatalogDocument, groupId: String?): List<ConnectionCard> {
    val sources = document.sources.associateBy { it.id }
    return document.nodes.asSequence()
        .filter { groupId == null || it.groupId == groupId }
        .map { node ->
            val source = sources[node.sourceId]
            ConnectionCard(
                id = node.id,
                sourceId = node.sourceId,
                title = node.displayName,
                subtitle = "${node.host}:${node.port} · ${node.protocol.name.lowercase()}",
                engine = source?.label ?: source?.adapterId ?: "Подключение",
                active = node.active,
                testable = node.testable,
                activatable = node.activatable,
            )
        }
        .toList()
}

internal fun operationMessage(result: String, error: String?): String = when (result) {
    "committed" -> "Готово"
    "rolled_back" -> "Изменение отменено, прежний маршрут восстановлен"
    "uncertain" -> "Состояние требует повторной проверки"
    else -> when (error) {
        "stale_state", "stale_adapter_state" -> "Список изменился — обновите его и повторите"
        else -> "Операция не выполнена"
    }
}
