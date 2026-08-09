package ru.anisimov.keenwg.domain.model

sealed interface RouterProfilesState {
    data object Loading : RouterProfilesState
    data class Ready(val profiles: List<RouterProfile>, val selectedId: String) : RouterProfilesState
    data class Locked(val reason: String) : RouterProfilesState
}
