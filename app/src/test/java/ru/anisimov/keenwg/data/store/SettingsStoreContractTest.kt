package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.anisimov.keenwg.domain.model.ServerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `router profile secrets are encrypted without a flat collector token mirror`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { temporaryFolder.newFile("settings.preferences_pb") },
        )
        var encryptedPlain = ""
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String): String {
                encryptedPlain = plain
                return "opaque-ciphertext"
            }
            override fun decrypt(blob: String) = when (blob) {
                "opaque-ciphertext" -> encryptedPlain
                else -> error("Unexpected ciphertext")
            }
        }
        val store = SettingsStore(dataStore, cipher)

        store.save(ServerSettings(collectorToken = "collector-secret"))

        assertEquals("collector-secret", store.settings.first().collectorToken)
        val preferences = dataStore.data.first()
        val raw = preferences[SettingsKeys.secrets].orEmpty()
        assertEquals("opaque-ciphertext", raw)
        assertFalse(raw.contains("collector-secret"))
        assertNull(preferences[stringPreferencesKey("collector_token_enc")])
    }
}
