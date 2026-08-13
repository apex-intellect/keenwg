package ru.anisimov.keenwg.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun `empty application locale follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
    }

    @Test fun `supported regional tags resolve to their language`() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguageTags("ru-RU"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
    }

    @Test fun `picker emits Android language tags and an empty system selection`() {
        assertEquals("", AppLanguage.SYSTEM.languageTags)
        assertEquals("ru", AppLanguage.RUSSIAN.languageTags)
        assertEquals("en", AppLanguage.ENGLISH.languageTags)
    }
}
