package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object SettingsKeys {
    val host = stringPreferencesKey("host")
    val port = intPreferencesKey("port")
    val login = stringPreferencesKey("login")
    val pass = stringPreferencesKey("pass_enc")
    val iface = stringPreferencesKey("iface")
    val serverKey = stringPreferencesKey("srv_key")
    val endpoint = stringPreferencesKey("endpoint")
    val subnet = stringPreferencesKey("subnet")
    val dns = stringPreferencesKey("dns")
    val mtu = intPreferencesKey("mtu")
    val keepalive = intPreferencesKey("keepalive")
    val collectorUrl = stringPreferencesKey("collector_url")
    val collectorToken = stringPreferencesKey("collector_token_enc")
    val xkeenControllerUrl = stringPreferencesKey("xkeen_controller_url")
    val xkeenControllerToken = stringPreferencesKey("xkeen_controller_token_enc")
    val profiles = stringPreferencesKey("router_profiles_json")
    val secrets = stringPreferencesKey("router_secrets_enc")
    val migrationReviewPending = booleanPreferencesKey("migration_0_7_review_pending")
}
