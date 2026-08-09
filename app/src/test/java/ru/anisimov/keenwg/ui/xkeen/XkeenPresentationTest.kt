package ru.anisimov.keenwg.ui.xkeen

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenOperation
import ru.anisimov.keenwg.data.xkeen.XkeenOperationResult
import ru.anisimov.keenwg.data.xkeen.XkeenOperationState
import ru.anisimov.keenwg.data.xkeen.XkeenActiveNode
import ru.anisimov.keenwg.data.xkeen.XkeenStatus
import ru.anisimov.keenwg.data.xkeen.XkeenSubscription

class XkeenPresentationTest {
    @Test fun `legacy chrome warning is ignored`() {
        assertNull(warningLabel(node(fingerprint = "chrome", warnings = listOf("fingerprint_chrome_unstable"))))
    }

    @Test fun `unrelated configuration warning remains visible`() {
        assertEquals(
            "Узел содержит предупреждение конфигурации",
            warningLabel(node(warnings = listOf("another_warning"))),
        )
    }

    @Test fun `operation result states rollback certainty`() {
        assertEquals("Узел переключён и проверен", operationMessage(result(XkeenOperationResult.SUCCESS)))
        assertEquals("Переключение не удалось; прежний узел восстановлен", operationMessage(result(XkeenOperationResult.FAILED_ROLLED_BACK)))
        assertEquals("Изменения не применялись", operationMessage(result(XkeenOperationResult.FAILED_NO_CHANGE)))
        assertEquals("Состояние XKeen требует проверки", operationMessage(result(XkeenOperationResult.UNCERTAIN)))
    }

    @Test fun `refresh success and timestamps use direct language`() {
        assertEquals("Подписка обновлена", operationMessage(result(XkeenOperationResult.SUCCESS, "refresh")))
        assertEquals("Подписка ещё не обновлялась", lastRefreshLabel(null))
        assertEquals("Подписка обновлена 2 янв., 03:04", lastRefreshLabel(1_704_164_640, ZoneOffset.UTC))
    }

    @Test fun `node subtitle contains only public routing fields`() {
        assertEquals("nl.example:443 · Reality / TCP · chrome", nodeSubtitle(node()))
    }

    @Test fun `leading country flag is rendered only once`() {
        assertEquals("Нидерланды 1", cleanNodeName("🇳🇱", "🇳🇱 Нидерланды 1"))
        assertEquals("Нидерланды 1", cleanNodeName("🇳🇱", "Нидерланды 1"))
        assertEquals("🇳🇱 резерв", cleanNodeName("🇳🇱", "🇳🇱 🇳🇱 резерв"))
        assertEquals("Сервер", cleanNodeName("🇳🇱", "   🇳🇱   "))
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

    private fun result(value: XkeenOperationResult, kind: String = "select") = XkeenOperation(
        idempotencyKey = "11111111-1111-4111-8111-111111111111",
        kind = kind,
        state = XkeenOperationState.TERMINAL,
        result = value,
        startedAt = 1,
        finishedAt = 2,
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
