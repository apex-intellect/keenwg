package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExpertModeStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `expert mode is off until the owner explicitly enables it`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { temporaryFolder.newFile("ui.preferences_pb") },
        )
        val store = ExpertModeStore(dataStore)

        assertFalse(store.enabled.first())
        store.setEnabled(true)
        assertTrue(store.enabled.first())
        store.setEnabled(false)
        assertFalse(store.enabled.first())
    }
}
