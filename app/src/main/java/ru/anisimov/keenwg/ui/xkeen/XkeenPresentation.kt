package ru.anisimov.keenwg.ui.xkeen

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenStatus

fun cleanNodeName(flag: String?, displayName: String, fallback: String): String {
    val trimmed = displayName.trim()
    val cleaned = if (!flag.isNullOrBlank() && trimmed.startsWith(flag)) {
        trimmed.removePrefix(flag).trimStart()
    } else {
        trimmed
    }
    return cleaned.ifBlank { fallback }
}

fun showExceptionalActiveCard(status: XkeenStatus): Boolean {
    val active = status.active ?: return false
    return active.missingFromSubscription || status.subscription.nodes.none { it.id == active.id }
}

fun nodeSubtitle(node: XkeenNode): String =
    "${node.host}:${node.port} · ${node.security.replaceFirstChar(Char::uppercase)} / ${node.transport.uppercase()} · ${node.fingerprint}"

fun hasConfigurationWarning(node: XkeenNode): Boolean =
    node.warnings.any { it != "fingerprint_chrome_unstable" }

fun formatRefreshTimestamp(
    epochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = Instant.ofEpochSecond(epochSeconds)
    .atZone(zoneId)
    .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))
