package ru.anisimov.keenwg.ui.navigation

import ru.anisimov.keenwg.data.companion.CapabilityDocument

enum class TopLevelDestination(val routeKey: String) {
    OVERVIEW("overview"),
    CONNECTIONS("connections"),
    ROUTES("routes"),
    ACCESS("access"),
    SYSTEM("system"),
}

fun visibleTopLevelDestinations(
    document: CapabilityDocument?,
    locked: Boolean,
): List<TopLevelDestination> {
    if (locked) return listOf(TopLevelDestination.OVERVIEW, TopLevelDestination.SYSTEM)
    val available = document?.capabilities.orEmpty().filter { it.available }.map { it.id }
    return buildList {
        add(TopLevelDestination.OVERVIEW)
        if (available.any { it.startsWith("connections.") }) add(TopLevelDestination.CONNECTIONS)
        if (available.any { it.startsWith("routes.") || it == "network.home_devices" }) add(TopLevelDestination.ROUTES)
        if (available.any { it.startsWith("access.") }) add(TopLevelDestination.ACCESS)
        add(TopLevelDestination.SYSTEM)
    }
}

fun preserveTopLevelDestination(
    current: TopLevelDestination,
    available: List<TopLevelDestination>,
): TopLevelDestination = current.takeIf(available::contains) ?: TopLevelDestination.OVERVIEW
