package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class RouterProfileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `legacy settings migrate once without losing encrypted credentials`() = runTest {
        val dataStore = testDataStore("migration.preferences_pb")
        val cipher = PrefixCipher()
        val legacy = ServerSettings(
            host = "192.168.1.1",
            port = 8080,
            login = "admin-user",
            password = "router-secret",
            interfaceId = "Wireguard9",
            serverPublicKey = "server-key",
            endpoint = "vpn.example.test:51820",
            subnetBase = "10.9.0.",
            dns = "192.168.1.1",
            mtu = 1360,
            keepalive = 17,
            collectorUrl = "http://192.168.1.1:18777",
            collectorToken = "collector-secret",
            xkeenControllerUrl = "http://192.168.1.1:18778",
            xkeenControllerToken = "xkeen-secret",
        )
        writeLegacy(dataStore, cipher, legacy)
        val store = RouterProfileStore(dataStore, cipher)

        store.migrateLegacy()
        val firstRaw = dataStore.data.first()[stringPreferencesKey("router_profiles_json")]
        store.migrateLegacy()
        val secondRaw = dataStore.data.first()[stringPreferencesKey("router_profiles_json")]

        val state = store.state.first() as RouterProfilesState.Ready
        assertEquals(1, state.profiles.size)
        assertEquals(state.profiles.single().id, state.selectedId)
        assertEquals(firstRaw, secondRaw)
        assertEquals(legacy, store.activeSettings.first())
        val rawSecrets = dataStore.data.first()[stringPreferencesKey("router_secrets_enc")].orEmpty()
        assertTrue(rawSecrets.startsWith("enc:"))
        assertTrue(!rawSecrets.contains("router-secret"))
        assertTrue(!rawSecrets.contains("xkeen-secret"))
        assertTrue(store.migrationReviewPending.first())
        store.dismissMigrationReview()
        assertTrue(!store.migrationReviewPending.first())
    }

    @Test fun `multiple profiles keep independent secrets and explicit selection`() = runTest {
        val dataStore = testDataStore("multiple.preferences_pb")
        val cipher = PrefixCipher()
        val store = RouterProfileStore(dataStore, cipher)
        store.migrateLegacy()
        val first = (store.state.first() as RouterProfilesState.Ready).profiles.single()
        val secondSettings = ServerSettings(host = "10.10.0.1", login = "second", password = "second-secret")
        val second = RouterProfile.fromServerSettings("router-two", "Office", secondSettings)

        store.upsert(second, RouterSecrets.fromServerSettings(secondSettings), select = true)
        assertEquals("10.10.0.1", store.activeSettings.first()!!.host)
        assertEquals("second-secret", store.activeSettings.first()!!.password)
        store.select(first.id)
        assertEquals(first.host, store.activeSettings.first()!!.host)
        assertNotEquals(second.id, (store.state.first() as RouterProfilesState.Ready).selectedId)
    }

    @Test fun `last profile cannot be deleted`() = runTest {
        val store = RouterProfileStore(testDataStore("delete.preferences_pb"), PrefixCipher())
        store.migrateLegacy()
        val only = (store.state.first() as RouterProfilesState.Ready).profiles.single()
        val result = runCatching { store.delete(only.id) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, (store.state.first() as RouterProfilesState.Ready).profiles.size)
    }

    @Test fun `secret decrypt failure exposes locked state and no active settings`() = runTest {
        val dataStore = testDataStore("locked.preferences_pb")
        val profile = RouterProfile.fromServerSettings("router-one", "Home", ServerSettings())
        val index = RouterProfileIndex(schemaVersion = 1, selectedProfileId = profile.id, profiles = listOf(profile))
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("router_profiles_json")] = RouterProfileCodec.encodeIndex(index)
            preferences[stringPreferencesKey("router_secrets_enc")] = "broken"
        }
        val store = RouterProfileStore(dataStore, object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(blob: String): String = error("keystore invalidated")
        })

        val state = store.state.first()
        assertTrue(state is RouterProfilesState.Locked)
        assertEquals(null, store.activeSettings.first())
    }

    @Test fun `codec rejects unknown schema version`() {
        val error = runCatching {
            RouterProfileCodec.decodeIndex("""{"schema_version":2,"selected_profile_id":"x","profiles":[]}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun `companion identity and device token are saved in one selected profile update`() = runTest {
        val store = RouterProfileStore(testDataStore("companion.preferences_pb"), PrefixCipher())
        store.migrateLegacy()
        val selected = (store.state.first() as RouterProfilesState.Ready).selectedId

        store.saveCompanion(
            selected,
            "https://10.8.0.1:18779",
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "device-token",
            "phone-1",
        )

        val active = store.activeProfile.first()!!
        assertEquals("https://10.8.0.1:18779", active.profile.companionUrl)
        assertEquals("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", active.profile.certificatePin)
        assertEquals("device-token", active.secrets.companionToken)
        assertEquals("phone-1", active.secrets.companionDeviceId)

        store.clearCompanion(selected)
        val cleared = store.activeProfile.first()!!
        assertEquals("", cleared.profile.companionUrl)
        assertEquals("", cleared.profile.certificatePin)
        assertEquals("", cleared.secrets.companionToken)
        assertEquals("", cleared.secrets.companionDeviceId)
    }

    private fun testDataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = TestScope(UnconfinedTestDispatcher()),
        produceFile = { temporaryFolder.newFile(name) },
    )
}

private class PrefixCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(blob: String): String {
        require(blob.startsWith("enc:"))
        return blob.removePrefix("enc:").reversed()
    }
}

private suspend fun writeLegacy(
    dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    cipher: SecretCipher,
    settings: ServerSettings,
) {
    dataStore.edit { p ->
        p[stringPreferencesKey("host")] = settings.host
        p[intPreferencesKey("port")] = settings.port
        p[stringPreferencesKey("login")] = settings.login
        p[stringPreferencesKey("pass_enc")] = cipher.encrypt(settings.password)
        p[stringPreferencesKey("iface")] = settings.interfaceId
        p[stringPreferencesKey("srv_key")] = settings.serverPublicKey
        p[stringPreferencesKey("endpoint")] = settings.endpoint
        p[stringPreferencesKey("subnet")] = settings.subnetBase
        p[stringPreferencesKey("dns")] = settings.dns
        p[intPreferencesKey("mtu")] = settings.mtu
        p[intPreferencesKey("keepalive")] = settings.keepalive
        p[stringPreferencesKey("collector_url")] = settings.collectorUrl
        p[stringPreferencesKey("collector_token_enc")] = cipher.encrypt(settings.collectorToken)
        p[stringPreferencesKey("xkeen_controller_url")] = settings.xkeenControllerUrl
        p[stringPreferencesKey("xkeen_controller_token_enc")] = cipher.encrypt(settings.xkeenControllerToken)
    }
}
