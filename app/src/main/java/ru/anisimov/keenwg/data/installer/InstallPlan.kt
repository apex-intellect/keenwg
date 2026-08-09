package ru.anisimov.keenwg.data.installer

data class InstallPlan(
    val version: String,
    val secureBaseUrl: String,
    val requiredBytes: Long,
    val effects: List<String>,
)

data class InstallPreparation(
    val profileId: String,
    val endpoint: SshEndpoint,
    val hostKey: HostKeyObservation,
    val probe: InstallProbe,
    val plan: InstallPlan,
)

enum class InstallPhase {
    VERIFY_ASSET,
    CONNECT,
    PROBE,
    UPLOAD,
    INSTALL,
    PAIRING_OFFER,
    PAIRING_EXCHANGE,
    HEALTH,
    SAVE_PROFILE,
    CLEANUP,
}

data class InstallReport(
    val version: String,
    val secureBaseUrl: String,
    val deviceId: String,
    val cleanupSucceeded: Boolean,
)

class InstallerException(
    val phase: InstallPhase,
    val safeMessage: String,
    val rollbackVerified: Boolean,
    cause: Throwable? = null,
) : Exception(safeMessage, cause)
