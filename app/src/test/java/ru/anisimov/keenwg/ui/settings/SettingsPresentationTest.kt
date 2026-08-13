package ru.anisimov.keenwg.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.discovery.DiscoveryPreview
import ru.anisimov.keenwg.domain.model.ServerSettings

class SettingsPresentationTest {
    @Test fun `numeric drafts reject empty input instead of silently restoring old value`() {
        val draft = ServerSettings(port = 80, mtu = 1380, keepalive = 25)

        assertTrue(parseNumericSettings(draft, "", "1380", "25").isFailure)
        assertTrue(parseNumericSettings(draft, "80", "", "25").isFailure)
        assertTrue(parseNumericSettings(draft, "80", "1380", "").isFailure)
        assertEquals(8080, parseNumericSettings(draft, "8080", "1420", "15").getOrThrow().port)
    }

    @Test fun `discovery preview names changed and preserved fields`() {
        val current = ServerSettings(
            interfaceId = "Wireguard0",
            serverPublicKey = "old",
            endpoint = "vpn.example:51820",
        )
        val rows = discoveryPreviewRows(
            current,
            DiscoveryPreview(
                interfaceId = "Wireguard0",
                serverPublicKey = "new",
                reviewedEndpoint = current.endpoint,
                endpointCandidate = null,
            ),
            DiscoveryPreviewLabels(
                interfaceLabel = "Interface",
                publicKeyLabel = "Public key",
                endpointLabel = "Endpoint",
                notSetLabel = "Not set",
            ),
        )

        assertFalse(rows.single { it.label == "Interface" }.changed)
        assertTrue(rows.single { it.label == "Public key" }.changed)
        assertFalse(rows.single { it.label == "Endpoint" }.changed)
        assertEquals(current.endpoint, rows.single { it.label == "Endpoint" }.value)
    }
}
