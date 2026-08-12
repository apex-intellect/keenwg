package ru.anisimov.keenwg.ui.network

import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.network.DomainRule

fun segmentCount(segment: NetworkSegment, state: NetworkUiState): Int = when (segment) {
    NetworkSegment.DEVICES -> state.devices.size
    NetworkSegment.IP_ADDRESSES -> state.exclusions?.entries?.size ?: 0
    NetworkSegment.DOMAINS -> state.domains?.rules?.size ?: 0
    NetworkSegment.EXPLAIN -> state.routeExplanation?.steps?.size ?: 0
    NetworkSegment.SCENARIOS -> state.scenarioCatalog?.presets?.size ?: 0
}

fun domainEffectLabel(effect: String): String = if (effect == "vpn") "Через VPN" else "Напрямую"

fun domainSourceLabel(source: String): String = when (source) {
    "zone" -> "зона"
    "geosite" -> "GeoSite"
    "system" -> "системное"
    else -> "вручную"
}

fun domainMatcherLabel(rule: DomainRule): String = when (rule.kind) {
    "suffix" -> "Зона · .${displayZone(rule.value)}"
    "geosite" -> "GeoSite · ${rule.value}"
    else -> "Домен · ${rule.value}"
}

fun canEditDomainRule(rule: DomainRule): Boolean = !rule.isProtected && rule.source != "system"

internal fun domainRuleKindResource(kind: String): Int = when (kind) {
    "suffix" -> R.string.rules_site_zone
    "geosite" -> R.string.rules_site_category
    else -> R.string.rules_site_domain
}

private fun displayZone(value: String): String = when (value) {
    "xn--p1ai" -> "рф"
    else -> value
}
