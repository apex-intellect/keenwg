package ru.anisimov.keenwg.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.backup.BackupApplyResult
import ru.anisimov.keenwg.data.backup.BackupGateway
import ru.anisimov.keenwg.data.backup.BackupPreview
import ru.anisimov.keenwg.data.store.ActiveRouterProfile

enum class BackupUiError {
    COMPANION_REQUIRED,
    ARCHIVE_REQUIRED,
    REVIEW_REQUIRED,
    CREATE_FAILED,
    PREVIEW_FAILED,
    APPLY_FAILED,
}

data class BackupUiState(
    val busy: Boolean = false,
    val archive: ByteArray? = null,
    val preview: BackupPreview? = null,
    val result: BackupApplyResult? = null,
    val error: BackupUiError? = null,
)

class BackupViewModel(
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val gateway: BackupGateway,
) : ViewModel() {
    constructor() : this(ServiceLocator.routerProfileStore.activeProfile, ServiceLocator.backupGateway)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun loadArchive(bytes: ByteArray) {
        if (_state.value.busy || bytes.size !in 1..MAX_ARCHIVE_BYTES) {
            _state.value = _state.value.copy(error = BackupUiError.ARCHIVE_REQUIRED)
            return
        }
        _state.value.archive?.fill(0)
        _state.value = BackupUiState(archive = bytes.copyOf())
    }

    fun create(passphrase: CharArray): Job = run(passphrase, BackupUiError.CREATE_FAILED) { active, secret ->
        val export = gateway.create(active.profile, active.secrets.companionToken, secret)
        _state.value.archive?.fill(0)
        _state.value = BackupUiState(archive = export.archive.copyOf(), preview = export.preview)
    }

    fun preview(passphrase: CharArray): Job = run(passphrase, BackupUiError.PREVIEW_FAILED) { active, secret ->
        val archive = _state.value.archive ?: throw BackupInputException(BackupUiError.ARCHIVE_REQUIRED)
        val preview = gateway.preview(active.profile, active.secrets.companionToken, archive, secret)
        _state.value = _state.value.copy(preview = preview, result = null, error = null)
    }

    fun apply(passphrase: CharArray, reviewedPlanId: String): Job =
        run(passphrase, BackupUiError.APPLY_FAILED) { active, secret ->
            val archive = _state.value.archive ?: throw BackupInputException(BackupUiError.ARCHIVE_REQUIRED)
            val preview = _state.value.preview
            if (preview == null || preview.planId != reviewedPlanId) {
                throw BackupInputException(BackupUiError.REVIEW_REQUIRED)
            }
            val result = gateway.apply(active.profile, active.secrets.companionToken, archive, secret, reviewedPlanId)
            _state.value = _state.value.copy(result = result, error = null)
        }

    fun clearSensitiveState() {
        if (_state.value.busy) return
        _state.value.archive?.fill(0)
        _state.value = BackupUiState()
    }

    private fun run(
        passphrase: CharArray,
        fallback: BackupUiError,
        operation: suspend (ActiveRouterProfile, String) -> Unit,
    ): Job = viewModelScope.launch {
        if (!mutex.tryLock()) {
            passphrase.fill('\u0000')
            return@launch
        }
        _state.value = _state.value.copy(busy = true, error = null, result = null)
        var transient = ""
        try {
            val active = activeProfileFlow.first()
            if (active == null || active.profile.companionUrl.isBlank() || active.secrets.companionToken.isBlank()) {
                throw BackupInputException(BackupUiError.COMPANION_REQUIRED)
            }
            transient = passphrase.concatToString()
            operation(active, transient)
        } catch (failure: BackupInputException) {
            _state.value = _state.value.copy(error = failure.code)
        } catch (_: Exception) {
            _state.value = _state.value.copy(error = fallback)
        } finally {
            passphrase.fill('\u0000')
            transient = ""
            _state.value = _state.value.copy(busy = false)
            mutex.unlock()
        }
    }

    override fun onCleared() {
        _state.value.archive?.fill(0)
        super.onCleared()
    }

    private class BackupInputException(val code: BackupUiError) : Exception()

    private companion object {
        const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
    }
}
