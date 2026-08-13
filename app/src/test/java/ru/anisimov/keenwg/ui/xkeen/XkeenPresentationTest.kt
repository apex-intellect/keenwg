package ru.anisimov.keenwg.ui.xkeen

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenActiveNode
import ru.anisimov.keenwg.data.xkeen.XkeenStatus
import ru.anisimov.keenwg.data.xkeen.XkeenSubscription

class XkeenPresentationTest {
    @Test fun `legacy chrome warning is ignored`() {
        assertFalse(hasConfigurationWarning(node(fingerprint = "chrome", warnings = listOf("fingerprint_chrome_unstable"))))
    }

    @Test fun `unrelated configuration warning remains visible`() {
        assertTrue(hasConfigurationWarning(node(warnings = listOf("another_warning"))))
    }

    @Test fun `refresh timestamp respects the selected locale`() {
        assertEquals("2 Jan, 03:04", formatRefreshTimestamp(1_704_164_640, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test fun `node subtitle contains only public routing fields`() {
        assertEquals("nl.example:443 · Reality / TCP · chrome", nodeSubtitle(node()))
    }

    @Test fun `leading country flag is rendered only once`() {
        assertEquals("Нидерланды 1", cleanNodeName("🇳🇱", "🇳🇱 Нидерланды 1", "Server"))
        assertEquals("Нидерланды 1", cleanNodeName("🇳🇱", "Нидерланды 1", "Server"))
        assertEquals("🇳🇱 резерв", cleanNodeName("🇳🇱", "🇳🇱 🇳🇱 резерв", "Server"))
        assertEquals("Server", cleanNodeName("🇳🇱", "   🇳🇱   ", "Server"))
    }

    @Test fun `separate active card is exceptional only`() {
        assertEquals(false, showExceptionalActiveCard(status(missing = false, includeActive = true)))
        assertEquals(true, showExceptionalActiveCard(status(missing = true, includeActive = true)))
        assertEquals(true, showExceptionalActiveCard(status(missing = false, includeActive = false)))
    }

    private fun node(
        fingerprint: String = "chrome",
        warnings: List<String> = emptyList(),
    ) = XkeenNode(
        id = "aabbccddeeff00112233445566778899",
        displayName = "Нидерланды",
        host = "nl.example",
        port = 443,
        fingerprint = fingerprint,
        transport = "tcp",
        security = "reality",
        flow = "xtls-rprx-vision",
        active = false,
        warnings = warnings,
    )

    private fun status(missing: Boolean, includeActive: Boolean): XkeenStatus {
        val active = XkeenActiveNode(
            id = "nl1",
            displayName = "🇳🇱 Нидерланды 1",
            flag = "🇳🇱",
            host = "nl.example",
            port = 443,
            fingerprint = "chrome",
            transport = "tcp",
            security = "reality",
            flow = "xtls-rprx-vision",
            active = true,
            resolvedIp = "192.0.2.1",
            confirmedAt = 1,
            missingFromSubscription = missing,
        )
        return XkeenStatus(
            version = "0.5",
            stateVersion = 1,
            active = active,
            subscription = XkeenSubscription(
                refreshedAt = 1,
                stale = false,
                nodes = if (includeActive) listOf(node().copy(id = "nl1")) else listOf(node().copy(id = "de1")),
            ),
        )
    }
}
