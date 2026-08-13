package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.data.installer.HostKeyObservation
import ru.anisimov.keenwg.data.installer.SshEndpoint

@OptIn(ExperimentalCoroutinesApi::class)
class RouterProfileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `fresh install starts with the common local Keenetic address`() = runTest {
        val store = RouterProfileStore(testDataStore("fresh.preferences_pb"), PrefixCipher())

        store.initialize()

        assertEquals("192.168.1.1", store.activeProfile.first()!!.profile.host)
    }

    @Test fun `schema one profile upgrades once without losing active encrypted credentials`() = runTest {
        val dataStore = testDataStore("migration.preferences_pb")
        val cipher = PrefixCipher()
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("router_profiles_json")] = schemaOneIndex()
            preferences[stringPreferencesKey("router_secrets_enc")] = cipher.encrypt(schemaOneSecrets())
            preferences[stringPreferencesKey("host")] = "obsolete-mirror"
            preferences[stringPreferencesKey("xkeen_controller_url")] = "http://192.168.1.1:18778"
            preferences[stringPreferencesKey("xkeen_controller_token_enc")] = cipher.encrypt("obsolete-token")
        }
        val store = RouterProfileStore(dataStore, cipher)

        store.initialize()
        val firstRaw = dataStore.data.first()[stringPreferencesKey("router_profiles_json")]
        store.initialize()
        val secondRaw = dataStore.data.first()[stringPreferencesKey("router_profiles_json")]

        val state = store.state.first() as RouterProfilesState.Ready
        assertEquals(1, state.profiles.size)
        assertEquals("home", state.selectedId)
        assertEquals(4, state.profiles.single().schemaVersion)
        assertEquals(firstRaw, secondRaw)
        val active = store.activeProfile.first()!!
        assertEquals("router-secret", active.secrets.rciPassword)
        assertEquals("device-token", active.secrets.companionToken)
        assertEquals("https://192.168.1.1:18779", active.profile.companionUrl)
        val rawSecrets = dataStore.data.first()[stringPreferencesKey("router_secrets_enc")].orEmpty()
        assertTrue(rawSecrets.startsWith("enc:"))
        assertTrue(!rawSecrets.contains("router-secret"))
        assertFalse(cipher.decrypt(rawSecrets).contains("collector", ignoreCase = true))
        val migratedPreferences = dataStore.data.first()
        assertEquals(null, migratedPreferences[stringPreferencesKey("host")])
        assertEquals(null, migratedPreferences[stringPreferencesKey("xkeen_controller_url")])
        assertEquals(null, migratedPreferences[stringPreferencesKey("xkeen_controller_token_enc")])
    }

    @Test fun `multiple profiles keep independent secrets and explicit selection`() = runTest {
        val dataStore = testDataStore("multiple.preferences_pb")
        val cipher = PrefixCipher()
        val store = RouterProfileStore(dataStore, cipher)
        store.initialize()
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
        store.initialize()
        val only = (store.state.first() as RouterProfilesState.Ready).profiles.single()
        val result = runCatching { store.delete(only.id) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, (store.state.first() as RouterProfilesState.Ready).profiles.size)
    }

    @Test fun `secret decrypt failure exposes locked state and no active settings`() = runTest {
        val dataStore = testDataStore("locked.preferences_pb")
        val profile = RouterProfile.fromServerSettings("router-one", "Home", ServerSettings())
        val index = RouterProfileIndex(schemaVersion = 4, selectedProfileId = profile.id, profiles = listOf(profile))
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

    @Test fun `schema two gains ssh defaults without losing router identity`() {
        val index = RouterProfileCodec.decodeIndex(schemaTwoIndex())

        assertEquals(4, index.schemaVersion)
        assertEquals("home", index.selectedProfileId)
        assertEquals("192.168.1.1", index.profiles.single().sshHost)
        assertEquals("https://192.168.1.1:18779", index.profiles.single().companionUrl)
    }

    @Test fun `codec rejects unknown schema version`() {
        val error = runCatching {
            RouterProfileCodec.decodeIndex("""{"schema_version":5,"selected_profile_id":"x","profiles":[]}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun `codec upgrades schema one and drops obsolete direct service credentials`() {
        val index = RouterProfileCodec.decodeIndex("""{
          "schema_version":1,"selected_profile_id":"home","profiles":[{
            "schemaVersion":1,"id":"home","displayName":"Home","host":"192.168.1.1","rciPort":80,
            "interfaceId":"Wireguard0","serverPublicKey":"","endpoint":"","subnetBase":"10.8.0.",
            "dns":"192.168.1.1","mtu":1380,"keepalive":25,
            "companionUrl":"https://192.168.1.1:18779","certificatePin":"sha256/pin",
            "collectorUrl":"http://192.168.1.1:18777","legacyXkeenUrl":"http://192.168.1.1:18778"
          }]
        }""")
        val secrets = RouterProfileCodec.decodeSecrets("""{
          "schema_version":1,"secrets":{"home":{
            "rciLogin":"admin","rciPassword":"router-secret","companionToken":"device-token",
            "companionDeviceId":"phone-1","collectorToken":"collector-token","legacyXkeenToken":"obsolete-token"
          }}
        }""")

        assertEquals(4, index.schemaVersion)
        assertEquals(4, index.profiles.single().schemaVersion)
        assertEquals("https://192.168.1.1:18779", index.profiles.single().companionUrl)
        assertEquals("device-token", secrets.getValue("home").companionToken)
        assertFalse(RouterProfileCodec.encodeIndex(index).contains("legacyXkeen"))
        assertFalse(RouterProfileCodec.encodeSecrets(secrets).contains("legacyXkeen"))
        assertFalse(RouterProfileCodec.encodeIndex(index).contains("collector", ignoreCase = true))
        assertFalse(RouterProfileCodec.encodeSecrets(secrets).contains("collector", ignoreCase = true))
    }

    @Test fun `current migration removes obsolete direct collector address and secret`() {
        val index = RouterProfileCodec.decodeIndex("""{
          "schema_version":3,"selected_profile_id":"home","profiles":[{
            "schemaVersion":3,"id":"home","displayName":"Home","host":"192.168.1.1","rciPort":80,
            "sshHost":"192.168.1.1","sshPort":222,"sshUsername":"root",
            "sshHostKeyAlgorithm":"","sshHostKeySha256":"",
            "interfaceId":"Wireguard0","serverPublicKey":"","endpoint":"","subnetBase":"10.8.0.",
            "dns":"192.168.1.1","mtu":1380,"keepalive":25,
            "companionUrl":"https://192.168.1.1:18779","certificatePin":"sha256/pin",
            "collectorUrl":"http://10.8.0.1:18777"
          }]
        }""")
        val secrets = RouterProfileCodec.decodeSecrets("""{
          "schema_version":2,"secrets":{"home":{
            "rciLogin":"admin","rciPassword":"router-secret","companionToken":"device-token",
            "companionDeviceId":"phone-1","collectorToken":"collector-secret"
          }}
        }""")

        assertEquals(4, index.schemaVersion)
        assertEquals(4, index.profiles.single().schemaVersion)
        assertFalse(RouterProfileCodec.encodeIndex(index).contains("collector", ignoreCase = true))
        assertFalse(RouterProfileCodec.encodeSecrets(secrets).contains("collector", ignoreCase = true))
        assertEquals("device-token", secrets.getValue("home").companionToken)
    }

    @Test fun `protected access identity trust and device token are saved in one selected profile update`() = runTest {
        val store = RouterProfileStore(testDataStore("companion.preferences_pb"), PrefixCipher())
        store.initialize()
        val selected = (store.state.first() as RouterProfilesState.Ready).selectedId
        val observation = HostKeyObservation(
            "ssh-ed25519",
            "SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg",
        )

        store.saveProtectedAccess(
            selected,
            SshEndpoint("192.168.1.2", 222, "root"),
            observation,
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
        assertEquals("192.168.1.1", active.profile.host)
        assertEquals("192.168.1.2", active.profile.sshHost)
        assertEquals(observation.sha256, active.profile.sshHostKeySha256)

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

private fun schemaOneIndex() = """{
  "schema_version":1,"selected_profile_id":"home","profiles":[{
    "schemaVersion":1,"id":"home","displayName":"Home","host":"192.168.1.1","rciPort":8080,
    "interfaceId":"Wireguard9","serverPublicKey":"server-key","endpoint":"vpn.example.test:51820",
    "subnetBase":"10.9.0.","dns":"192.168.1.1","mtu":1360,"keepalive":17,
    "companionUrl":"https://192.168.1.1:18779","certificatePin":"sha256/pin",
    "collectorUrl":"http://192.168.1.1:18777","legacyXkeenUrl":"http://192.168.1.1:18778"
  }]
}"""

private fun schemaOneSecrets() = """{
  "schema_version":1,"secrets":{"home":{
    "rciLogin":"admin-user","rciPassword":"router-secret","companionToken":"device-token",
    "companionDeviceId":"phone-1","collectorToken":"collector-secret","legacyXkeenToken":"obsolete-token"
  }}
}"""

private fun schemaTwoIndex() = """{
  "schema_version":2,"selected_profile_id":"home","profiles":[{
    "schemaVersion":2,"id":"home","displayName":"Home","host":"192.168.1.1","rciPort":8080,
    "interfaceId":"Wireguard9","serverPublicKey":"server-key","endpoint":"vpn.example.test:51820",
    "subnetBase":"10.9.0.","dns":"192.168.1.1","mtu":1360,"keepalive":17,
    "companionUrl":"https://192.168.1.1:18779","certificatePin":"sha256/pin",
    "collectorUrl":"http://192.168.1.1:18777"
  }]
}"""
