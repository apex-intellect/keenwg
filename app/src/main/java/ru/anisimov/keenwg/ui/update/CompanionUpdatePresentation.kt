package ru.anisimov.keenwg.ui.update

import ru.anisimov.keenwg.R

enum class UpdateAction { NONE, UPDATE, RETRY, CREDENTIAL_UPGRADE, DONE }

data class UpdatePresentation(val title: Int, val body: Int, val action: UpdateAction, val error: Boolean = false)

fun updatePresentation(phase: UpdatePhase): UpdatePresentation = when (phase) {
    UpdatePhase.LOADING -> UpdatePresentation(R.string.update_checking_title, R.string.update_checking_body, UpdateAction.NONE)
    UpdatePhase.NOT_CONFIGURED -> UpdatePresentation(R.string.update_not_configured_title, R.string.update_not_configured_body, UpdateAction.CREDENTIAL_UPGRADE)
    UpdatePhase.UNREACHABLE -> UpdatePresentation(R.string.update_unreachable_title, R.string.update_unreachable_body, UpdateAction.RETRY, true)
    UpdatePhase.PAIRING_REQUIRED -> UpdatePresentation(R.string.update_pairing_title, R.string.update_pairing_body, UpdateAction.CREDENTIAL_UPGRADE, true)
    UpdatePhase.INCOMPATIBLE -> UpdatePresentation(R.string.update_incompatible_title, R.string.update_incompatible_body, UpdateAction.CREDENTIAL_UPGRADE, true)
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
    UpdatePhase.CHECK_FAILED -> UpdatePresentation(R.string.update_check_failed_title, R.string.update_check_failed_body, UpdateAction.RETRY, true)
    UpdatePhase.ERROR -> UpdatePresentation(R.string.update_error_title, R.string.update_error_body, UpdateAction.RETRY, true)
}
