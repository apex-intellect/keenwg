package ru.anisimov.keenwg.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionStatusPresentationTest {
    @Test fun `missing protected endpoint stays honest about installation state`() {
        val checks = companionStatusChecks(
            CompanionStatusFacts(
                endpointConfigured = false,
                serviceReachable = null,
                storageReady = null,
                authorizationValid = null,
                apiCompatible = null,
                installedVersion = null,
                bundledVersion = "2.2.3",
            ),
        )

        assertEquals(CompanionCheckState.ERROR, checks.single { it.id == CompanionCheckId.CONFIGURATION }.state)
        assertEquals(CompanionCheckState.NOT_CHECKED, checks.single { it.id == CompanionCheckId.SERVICE }.state)
        assertEquals(CompanionCheckState.NOT_CHECKED, checks.single { it.id == CompanionCheckId.UPDATE }.state)
    }

    @Test fun `healthy current component exposes six successful checks`() {
        val checks = companionStatusChecks(
            CompanionStatusFacts(
                endpointConfigured = true,
                serviceReachable = true,
                storageReady = true,
                authorizationValid = true,
                apiCompatible = true,
                installedVersion = "2.2.3",
                bundledVersion = "2.2.3",
            ),
        )

        assertEquals(CompanionCheckId.entries, checks.map { it.id })
        assertEquals(List(CompanionCheckId.entries.size) { CompanionCheckState.OK }, checks.map { it.state })
    }

    @Test fun `newer bundled component marks only update check as attention`() {
        val checks = companionStatusChecks(
            CompanionStatusFacts(
                endpointConfigured = true,
                serviceReachable = true,
                storageReady = true,
                authorizationValid = true,
                apiCompatible = true,
                installedVersion = "2.2.2",
                bundledVersion = "2.2.3",
            ),
        )

        assertEquals(CompanionCheckState.ATTENTION, checks.single { it.id == CompanionCheckId.UPDATE }.state)
        assertEquals(CompanionCheckState.OK, checks.single { it.id == CompanionCheckId.API }.state)
    }

    @Test fun `lost phone authorization does not pretend api was checked`() {
        val checks = companionStatusChecks(
            CompanionStatusFacts(
                endpointConfigured = true,
                serviceReachable = true,
                storageReady = true,
                authorizationValid = false,
                apiCompatible = null,
                installedVersion = "2.2.3",
                bundledVersion = "2.2.3",
            ),
        )

        assertEquals(CompanionCheckState.ERROR, checks.single { it.id == CompanionCheckId.PHONE_ACCESS }.state)
        assertEquals(CompanionCheckState.NOT_CHECKED, checks.single { it.id == CompanionCheckId.API }.state)
    }

    @Test fun `component phase distinguishes setup connectivity pairing and compatibility`() {
        val base = CompanionStatusFacts(
            endpointConfigured = true,
            serviceReachable = true,
            storageReady = true,
            authorizationValid = true,
            apiCompatible = true,
            installedVersion = "2.2.3",
            bundledVersion = "2.2.3",
        )

        assertEquals(UpdatePhase.NOT_CONFIGURED, companionUpdatePhase(base.copy(endpointConfigured = false), true))
        assertEquals(UpdatePhase.UNREACHABLE, companionUpdatePhase(base.copy(serviceReachable = false), true))
        assertEquals(UpdatePhase.PAIRING_REQUIRED, companionUpdatePhase(base.copy(authorizationValid = false), true))
        assertEquals(UpdatePhase.INCOMPATIBLE, companionUpdatePhase(base.copy(apiCompatible = false), true))
        assertEquals(UpdatePhase.NEEDS_PASSWORD, companionUpdatePhase(base, false))
        assertEquals(UpdatePhase.CHECK_FAILED, companionUpdatePhase(base, null))
        assertEquals(UpdatePhase.UP_TO_DATE, companionUpdatePhase(base, true))
        assertEquals(UpdatePhase.AVAILABLE, companionUpdatePhase(base.copy(installedVersion = "2.2.2"), true))
    }
}
