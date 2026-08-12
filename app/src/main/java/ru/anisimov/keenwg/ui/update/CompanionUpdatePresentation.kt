package ru.anisimov.keenwg.ui.update

import ru.anisimov.keenwg.R

enum class UpdatePhase {
    LOADING, UP_TO_DATE, AVAILABLE, VERIFYING, UPLOADING, INSTALLING, RECONNECTING,
    SUCCESS, ROLLED_BACK, UNCERTAIN, NEEDS_PASSWORD, ERROR,
}

enum class UpdateAction { NONE, UPDATE, RETRY, CREDENTIAL_UPGRADE, DONE }

data class UpdatePresentation(val title: Int, val body: Int, val action: UpdateAction, val error: Boolean = false)

fun updatePresentation(phase: UpdatePhase): UpdatePresentation = when (phase) {
    UpdatePhase.LOADING -> UpdatePresentation(R.string.update_checking_title, R.string.update_checking_body, UpdateAction.NONE)
    UpdatePhase.UP_TO_DATE -> UpdatePresentation(R.string.update_current_title, R.string.update_current_body, UpdateAction.NONE)
    UpdatePhase.AVAILABLE -> UpdatePresentation(R.string.update_available_title, R.string.update_available_body, UpdateAction.UPDATE)
    UpdatePhase.VERIFYING -> UpdatePresentation(R.string.update_verifying_title, R.string.update_verifying_body, UpdateAction.NONE)
    UpdatePhase.UPLOADING -> UpdatePresentation(R.string.update_uploading_title, R.string.update_uploading_body, UpdateAction.NONE)
    UpdatePhase.INSTALLING -> UpdatePresentation(R.string.update_installing_title, R.string.update_installing_body, UpdateAction.NONE)
    UpdatePhase.RECONNECTING -> UpdatePresentation(R.string.update_reconnecting_title, R.string.update_reconnecting_body, UpdateAction.NONE)
    UpdatePhase.SUCCESS -> UpdatePresentation(R.string.update_success_title, R.string.update_success_body, UpdateAction.DONE)
    UpdatePhase.ROLLED_BACK -> UpdatePresentation(R.string.update_rollback_title, R.string.update_rollback_body, UpdateAction.RETRY, true)
    UpdatePhase.UNCERTAIN -> UpdatePresentation(R.string.update_uncertain_title, R.string.update_uncertain_body, UpdateAction.RETRY, true)
    UpdatePhase.NEEDS_PASSWORD -> UpdatePresentation(R.string.update_transition_title, R.string.update_transition_body, UpdateAction.CREDENTIAL_UPGRADE)
    UpdatePhase.ERROR -> UpdatePresentation(R.string.update_error_title, R.string.update_error_body, UpdateAction.RETRY, true)
}

fun compareUpdateVersions(left: String, right: String): Int {
    fun parse(value: String): List<Int>? {
        val stable = value.substringBefore('-')
        val parts = stable.split('.')
        if (parts.size != 3) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }
    val leftParts = parse(left) ?: return 0
    val rightParts = parse(right) ?: return 0
    for (index in 0..2) {
        if (leftParts[index] != rightParts[index]) return leftParts[index].compareTo(rightParts[index])
    }
    return 0
}
