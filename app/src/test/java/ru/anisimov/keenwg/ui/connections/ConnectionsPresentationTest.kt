package ru.anisimov.keenwg.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.data.catalog.CatalogGroup
import ru.anisimov.keenwg.data.catalog.CatalogSource
import ru.anisimov.keenwg.data.catalog.CatalogSubscriptionInfo
import ru.anisimov.keenwg.data.catalog.CatalogSourceKind
import ru.anisimov.keenwg.data.catalog.SourceStatus
import ru.anisimov.keenwg.data.catalog.SourceConfigurationStatus

class ConnectionsPresentationTest {
    @Test fun `reserved backend labels are translated at presentation boundary`() {
        assertEquals(SourceDisplayKind.XKEEN_SUBSCRIPTION, sourceDisplayKind(source()))
        assertEquals(GroupDisplayKind.PRIMARY, groupDisplayKind(CatalogGroup("primary", "Primary", 0)))
    }

    @Test fun `custom labels remain available to the interface`() {
        assertEquals(SourceDisplayKind.CUSTOM, sourceDisplayKind(source(id = "mine", adapterId = "catalog")))
        assertEquals(GroupDisplayKind.CUSTOM, groupDisplayKind(CatalogGroup("family", "Family", 1)))
    }

    @Test fun `owned subscriptions can refresh and private URLs never become titles`() {
        val source = source(id = "mine", adapterId = "catalog").copy(
            foreign = false,
            label = "https://provider.example/private-token",
        )
        assertEquals(true, sourceCanRefresh(source))
        assertEquals(null, sourceDisplayTitle(source))
        val catalog = ru.anisimov.keenwg.data.catalog.CatalogDocument(
            1, 1u, listOf(CatalogGroup("primary", "Primary", 0)), listOf(source),
            listOf(ru.anisimov.keenwg.data.catalog.CatalogNode(
                "node", source.id, "primary", "NL", "NL",
                ru.anisimov.keenwg.data.catalog.CatalogProtocol.VLESS, "vpn.example", 443,
                active = false, testable = true, activatable = true, warnings = emptyList(),
            )),
        )
        assertEquals("VPN", connectionCards(catalog, null).single().customSourceLabel)
    }

    @Test fun `provider metadata wins and traffic safely combines both directions`() {
        val source = source(id = "mine", adapterId = "catalog").copy(
            foreign = false,
            subscriptionInfo = CatalogSubscriptionInfo(
                profileTitle = "ScufVPN",
                uploadBytes = 10,
                downloadBytes = 20,
                totalBytes = 100,
                expiresAt = 1_850_601_905,
            ),
        )
        assertEquals("ScufVPN", sourceDisplayTitle(source))
        assertEquals(30L, subscriptionUsedBytes(source))
    }

    @Test fun `subscription result names the completed task`() {
        assertEquals(ConnectionNotice.SubscriptionUpdated(3), connectionOperationNotice("committed", null, 3))
        assertEquals(ConnectionNotice.SubscriptionDownloadFailed, connectionOperationNotice("rejected", "subscription_download_failed", 3))
        assertEquals(ConnectionNotice.InvalidSubscription, connectionOperationNotice("rejected", "invalid_subscription", 3))
        assertEquals(ConnectionNotice.RouterBusy, connectionOperationNotice("rejected", "busy", 3))
        assertEquals(ConnectionNotice.ReloadAndRetry, connectionOperationNotice("rejected", "stale_state", 3))
        assertEquals(ConnectionNotice.ResultUnconfirmed, connectionOperationNotice("uncertain", null, 3))
    }

    @Test fun `unconfigured XKeen source has an explicit add link mode`() {
        assertEquals(
            SubscriptionSourceMode.NEEDS_LINK,
            subscriptionSourceMode(source(), SourceConfigurationStatus(false), null),
        )
        assertEquals(
            SubscriptionSourceMode.READY,
            subscriptionSourceMode(source(), SourceConfigurationStatus(true), null),
        )
    }

    private fun source(
        id: String = "xkeen-subscription",
        adapterId: String = "xkeen",
    ) = CatalogSource(
        id = id,
        groupId = "primary",
        kind = CatalogSourceKind.SUBSCRIPTION,
        label = "XKeen",
        adapterId = adapterId,
        status = SourceStatus.READY,
        nodeCount = 3,
        warnings = emptyList(),
        foreign = true,
    )
}
