package ru.anisimov.keenwg.ui.network

import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.network.DomainRule
import java.net.IDN

fun segmentCount(segment: NetworkSegment, state: NetworkUiState): Int = when (segment) {
    NetworkSegment.DEVICES -> state.devices.size
    NetworkSegment.IP_ADDRESSES -> state.exclusions?.entries?.size ?: 0
    NetworkSegment.DOMAINS -> state.domains?.rules?.size ?: 0
    NetworkSegment.EXPLAIN -> state.routeExplanation?.steps?.size ?: 0
    NetworkSegment.SCENARIOS -> state.scenarioCatalog?.presets?.size ?: 0
}

fun domainEffectResource(effect: String): Int = if (effect == "vpn") R.string.rules_via_vpn else R.string.rules_direct

fun domainSourceResource(source: String): Int = when (source) {
    "zone" -> R.string.domain_source_zone
    "geosite" -> R.string.domain_source_geosite
    "system" -> R.string.domain_source_system
    else -> R.string.domain_source_manual
}

fun domainMatcherResource(rule: DomainRule): Int = when (rule.kind) {
    "suffix" -> R.string.domain_matcher_zone
    "geosite" -> R.string.domain_matcher_geosite
    else -> R.string.domain_matcher_domain
}

fun domainMatcherValue(rule: DomainRule): String =
    if (rule.kind == "suffix") IDN.toUnicode(rule.value) else rule.value

fun canEditDomainRule(rule: DomainRule): Boolean = !rule.isProtected && rule.source != "system"

internal fun domainRuleKindResource(kind: String): Int = when (kind) {
    "suffix" -> R.string.rules_site_zone
    "geosite" -> R.string.rules_site_category
    else -> R.string.rules_site_domain
}
