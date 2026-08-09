package ru.anisimov.keenwg.ui.xkeen

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenOperation
import ru.anisimov.keenwg.data.xkeen.XkeenOperationResult
import ru.anisimov.keenwg.data.xkeen.XkeenStatus

fun cleanNodeName(flag: String?, displayName: String): String {
    val trimmed = displayName.trim()
    val cleaned = if (!flag.isNullOrBlank() && trimmed.startsWith(flag)) {
        trimmed.removePrefix(flag).trimStart()
    } else {
        trimmed
    }
    return cleaned.ifBlank { "Сервер" }
}

fun showExceptionalActiveCard(status: XkeenStatus): Boolean {
    val active = status.active ?: return false
    return active.missingFromSubscription || status.subscription.nodes.none { it.id == active.id }
}

fun nodeSubtitle(node: XkeenNode): String =
    "${node.host}:${node.port} · ${node.security.replaceFirstChar(Char::uppercase)} / ${node.transport.uppercase()} · ${node.fingerprint}"

fun operationMessage(operation: XkeenOperation): String = when (operation.result) {
    XkeenOperationResult.SUCCESS -> if (operation.kind == "refresh") "Подписка обновлена" else "Узел переключён и проверен"
    XkeenOperationResult.FAILED_ROLLED_BACK -> "Переключение не удалось; прежний узел восстановлен"
    XkeenOperationResult.FAILED_NO_CHANGE -> "Изменения не применялись"
    XkeenOperationResult.UNCERTAIN -> "Состояние XKeen требует проверки"
    null -> "Операция XKeen ещё выполняется"
}

fun warningLabel(node: XkeenNode): String? =
    if (node.warnings.any { it != "fingerprint_chrome_unstable" }) "Узел содержит предупреждение конфигурации" else null

fun lastRefreshLabel(epochSeconds: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
    if (epochSeconds == null) return "Подписка ещё не обновлялась"
    val formatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru"))
    return "Подписка обновлена ${Instant.ofEpochSecond(epochSeconds).atZone(zoneId).format(formatter)}"
}
