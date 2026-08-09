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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.anisimov.keenwg.domain.model.ServerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `xkeen token is encrypted in raw preferences`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { temporaryFolder.newFile("settings.preferences_pb") },
        )
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "enc:$plain"
            override fun decrypt(blob: String) = blob.removePrefix("enc:")
        }
        val store = SettingsStore(dataStore, cipher)

        store.save(ServerSettings(xkeenControllerToken = "control-secret"))

        assertEquals("control-secret", store.settings.first().xkeenControllerToken)
        val raw = dataStore.data.first()[stringPreferencesKey("xkeen_controller_token_enc")].orEmpty()
        assertEquals("enc:control-secret", raw)
        assertFalse(raw == "control-secret")
    }
}
