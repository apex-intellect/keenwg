package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.data.catalog.CatalogDocument
import ru.anisimov.keenwg.data.catalog.CatalogGroup
import ru.anisimov.keenwg.data.catalog.CatalogSource
import ru.anisimov.keenwg.data.catalog.SourceConfigurationStatus

internal enum class SourceDisplayKind { XKEEN_SUBSCRIPTION, CUSTOM }

internal enum class GroupDisplayKind { PRIMARY, CUSTOM }

internal enum class SubscriptionSourceMode { CHECKING, NEEDS_LINK, READY }

sealed interface ConnectionNotice {
    data class SubscriptionUpdated(val serverCount: Int) : ConnectionNotice
    data object SubscriptionDownloadFailed : ConnectionNotice
    data object InvalidSubscription : ConnectionNotice
    data object RouterBusy : ConnectionNotice
    data object ReloadAndRetry : ConnectionNotice
    data object ResultUnconfirmed : ConnectionNotice
    data object ActionFailed : ConnectionNotice
}
internal fun sourceDisplayKind(source: CatalogSource): SourceDisplayKind =
    if (source.id == "xkeen-subscription" || source.adapterId == "xkeen") {
        SourceDisplayKind.XKEEN_SUBSCRIPTION
    } else {
        SourceDisplayKind.CUSTOM
    }

internal fun sourceCanRefresh(source: CatalogSource): Boolean = source.foreign ||
    (source.adapterId == "catalog" && source.kind in setOf(
        ru.anisimov.keenwg.data.catalog.CatalogSourceKind.SUBSCRIPTION,
        ru.anisimov.keenwg.data.catalog.CatalogSourceKind.SHARE_LINK,
    ))

internal fun sourceDisplayTitle(source: CatalogSource): String? {
    source.subscriptionInfo?.profileTitle?.let(::safeVisibleSourceLabel)?.let { return it }
    return safeVisibleSourceLabel(source.label)
}

private fun safeVisibleSourceLabel(value: String): String? = value.trim().takeIf {
    it.isNotEmpty() && !it.startsWith("http://", true) && !it.startsWith("https://", true)
}

internal fun subscriptionUsedBytes(source: CatalogSource): Long? {
    val info = source.subscriptionInfo ?: return null
    if (info.uploadBytes == null && info.downloadBytes == null) return null
    return (info.uploadBytes ?: 0L).let { upload ->
        val download = info.downloadBytes ?: 0L
        if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
    }
}

internal fun groupDisplayKind(group: CatalogGroup): GroupDisplayKind =
    if (group.id == "primary") GroupDisplayKind.PRIMARY else GroupDisplayKind.CUSTOM

internal fun subscriptionSourceMode(
    source: CatalogSource,
    status: SourceConfigurationStatus?,
    action: SourceActionState?,
): SubscriptionSourceMode = when {
    source.id != XKEEN_SUBSCRIPTION_SOURCE_ID -> SubscriptionSourceMode.READY
    action == SourceActionState.CHECKING_CONFIGURATION -> SubscriptionSourceMode.CHECKING
    status?.configured == false -> SubscriptionSourceMode.NEEDS_LINK
    else -> SubscriptionSourceMode.READY
}

internal fun connectionOperationNotice(
    result: String,
    error: String?,
    serverCount: Int,
): ConnectionNotice = when (result) {
    "committed" -> ConnectionNotice.SubscriptionUpdated(serverCount)
    "uncertain" -> ConnectionNotice.ResultUnconfirmed
    else -> when (error) {
        "subscription_download_failed" -> ConnectionNotice.SubscriptionDownloadFailed
        "invalid_subscription" -> ConnectionNotice.InvalidSubscription
        "busy" -> ConnectionNotice.RouterBusy
        "stale_state", "stale_adapter_state" -> ConnectionNotice.ReloadAndRetry
        else -> ConnectionNotice.ActionFailed
    }
}

internal data class ConnectionCard(
    val id: String,
    val sourceId: String,
    val title: String,
    val subtitle: String,
    val sourceKind: SourceDisplayKind,
    val customSourceLabel: String,
    val active: Boolean,
    val testable: Boolean,
    val activatable: Boolean,
)

internal fun connectionCards(document: CatalogDocument, groupId: String?): List<ConnectionCard> {
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
                sourceKind = source?.let(::sourceDisplayKind) ?: SourceDisplayKind.CUSTOM,
                customSourceLabel = source?.let(::sourceDisplayTitle) ?: "VPN",
                active = node.active,
                testable = node.testable,
                activatable = node.activatable,
            )
        }
        .toList()
}

internal const val XKEEN_SUBSCRIPTION_SOURCE_ID = "xkeen-subscription"
