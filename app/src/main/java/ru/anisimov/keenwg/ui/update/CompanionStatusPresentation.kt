package ru.anisimov.keenwg.ui.update

enum class UpdatePhase {
    LOADING,
    NOT_CONFIGURED,
    UNREACHABLE,
    PAIRING_REQUIRED,
    INCOMPATIBLE,
    UP_TO_DATE,
    AVAILABLE,
    VERIFYING,
    UPLOADING,
    INSTALLING,
    RECONNECTING,
    SUCCESS,
    ROLLED_BACK,
    UNCERTAIN,
    NEEDS_PASSWORD,
    CHECK_FAILED,
    ERROR,
}

enum class CompanionCheckId {
    CONFIGURATION,
    SERVICE,
    STORAGE,
    PHONE_ACCESS,
    API,
    UPDATE,
}

enum class CompanionCheckState {
    CHECKING,
    OK,
    ATTENTION,
    ERROR,
    NOT_CHECKED,
}

data class CompanionStatusCheck(
    val id: CompanionCheckId,
    val state: CompanionCheckState,
)

data class CompanionStatusFacts(
    val endpointConfigured: Boolean,
    val serviceReachable: Boolean?,
    val storageReady: Boolean?,
    val authorizationValid: Boolean?,
    val apiCompatible: Boolean?,
    val installedVersion: String?,
    val bundledVersion: String?,
)

fun companionStatusChecks(facts: CompanionStatusFacts): List<CompanionStatusCheck> = buildList {
    add(
        CompanionStatusCheck(
            CompanionCheckId.CONFIGURATION,
            if (facts.endpointConfigured) CompanionCheckState.OK else CompanionCheckState.ERROR,
        ),
    )
    add(CompanionStatusCheck(CompanionCheckId.SERVICE, facts.serviceReachable.toCheckState()))
    add(CompanionStatusCheck(CompanionCheckId.STORAGE, facts.storageReady.toCheckState()))
    add(CompanionStatusCheck(CompanionCheckId.PHONE_ACCESS, facts.authorizationValid.toCheckState()))
    add(CompanionStatusCheck(CompanionCheckId.API, facts.apiCompatible.toCheckState()))
    add(
        CompanionStatusCheck(
            CompanionCheckId.UPDATE,
            when {
                facts.installedVersion == null || facts.bundledVersion == null -> CompanionCheckState.NOT_CHECKED
                compareUpdateVersions(facts.bundledVersion, facts.installedVersion) > 0 -> CompanionCheckState.ATTENTION
                else -> CompanionCheckState.OK
            },
        ),
    )
}

fun companionUpdatePhase(facts: CompanionStatusFacts, updaterSupported: Boolean?): UpdatePhase = when {
    !facts.endpointConfigured -> UpdatePhase.NOT_CONFIGURED
    facts.serviceReachable == false -> UpdatePhase.UNREACHABLE
    facts.serviceReachable == null -> UpdatePhase.LOADING
    facts.authorizationValid == false -> UpdatePhase.PAIRING_REQUIRED
    facts.apiCompatible == false -> UpdatePhase.INCOMPATIBLE
    facts.authorizationValid == null -> UpdatePhase.LOADING
    facts.apiCompatible == null -> UpdatePhase.LOADING
    updaterSupported == false -> UpdatePhase.NEEDS_PASSWORD
    updaterSupported == null -> UpdatePhase.CHECK_FAILED
    facts.installedVersion == null || facts.bundledVersion == null -> UpdatePhase.ERROR
    compareUpdateVersions(facts.bundledVersion, facts.installedVersion) > 0 -> UpdatePhase.AVAILABLE
    else -> UpdatePhase.UP_TO_DATE
}

private fun Boolean?.toCheckState(): CompanionCheckState = when (this) {
    true -> CompanionCheckState.OK
    false -> CompanionCheckState.ERROR
    null -> CompanionCheckState.NOT_CHECKED
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
