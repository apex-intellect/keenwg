package ru.anisimov.keenwg.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionUpdatePresentationTest {
    @Test fun `every update phase has one plain language primary action`() {
        assertEquals(UpdateAction.UPDATE, updatePresentation(UpdatePhase.AVAILABLE).action)
        assertEquals(UpdateAction.NONE, updatePresentation(UpdatePhase.VERIFYING).action)
        assertEquals(UpdateAction.NONE, updatePresentation(UpdatePhase.UPLOADING).action)
        assertEquals(UpdateAction.NONE, updatePresentation(UpdatePhase.INSTALLING).action)
        assertEquals(UpdateAction.NONE, updatePresentation(UpdatePhase.RECONNECTING).action)
        assertEquals(UpdateAction.DONE, updatePresentation(UpdatePhase.SUCCESS).action)
        assertEquals(UpdateAction.RETRY, updatePresentation(UpdatePhase.ROLLED_BACK).action)
        assertEquals(UpdateAction.CREDENTIAL_UPGRADE, updatePresentation(UpdatePhase.NEEDS_PASSWORD).action)
    }

    @Test fun `semantic version comparison is numeric`() {
        assertEquals(1, compareUpdateVersions("2.10.0", "2.9.9"))
        assertEquals(0, compareUpdateVersions("2.2.0", "2.2.0"))
        assertEquals(-1, compareUpdateVersions("2.1.9", "2.2.0"))
    }
}
