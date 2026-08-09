package ru.anisimov.keenwg.data.rci

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.anisimov.keenwg.data.crypto.WgKeys

class RciCommandsTest {
    private val pub = WgKeys.generate().publicKey
    private val oldPub = WgKeys.generate().publicKey

    @Test fun add_batch() {
        val s = RciCommands.addPeer("Wireguard0", pub, "brat", "10.8.0.7", 25)
        assertTrue(s.contains("interface Wireguard0 wireguard peer $pub !brat"))
        assertTrue(s.contains("allow-ips 10.8.0.7 255.255.255.255"))
        assertTrue(s.trimStart().startsWith("["))
        assertTrue(s.contains("connect"))
    }

    @Test fun remove_form() {
        assertTrue(RciCommands.remove("Wireguard0", pub).contains("interface Wireguard0 no wireguard peer $pub"))
    }

    @Test fun disable_form() {
        assertTrue(RciCommands.setEnabled("Wireguard0", pub, false).contains("no connect"))
    }

    @Test fun save_form() {
        assertTrue(RciCommands.save.contains("\"save\":true"))
    }

    @Test fun create_candidate_is_disabled_and_has_no_ip_or_save() {
        val commands = parses(RciCommands.createPeer("Wireguard0", pub, "phone", 25))
        assertEquals(
            listOf(
                "interface Wireguard0 wireguard peer $pub !phone",
                "interface Wireguard0 wireguard peer $pub keepalive-interval 25",
            ),
            commands,
        )
        assertTrue(commands.none { it.contains("allow-ips") || it.endsWith(" connect") || it.contains("save") })
    }

    @Test fun cutover_removes_old_and_restores_full_snapshot_in_order() {
        val old = ConfiguredPeer(oldPub, "anna", "10.8.0.5", 25, true, emptyList())
        assertEquals(
            listOf(
                "interface Wireguard0 no wireguard peer $oldPub",
                "interface Wireguard0 wireguard peer $pub !anna",
                "interface Wireguard0 wireguard peer $pub allow-ips 10.8.0.5 255.255.255.255",
                "interface Wireguard0 wireguard peer $pub keepalive-interval 25",
                "interface Wireguard0 wireguard peer $pub connect",
            ),
            parses(RciCommands.cutoverPeer("Wireguard0", old, pub)),
        )
    }

    @Test fun rejects_command_injection_in_all_source_fields() {
        assertThrows(IllegalArgumentException::class.java) { RciCommands.rename("Wireguard0", pub, "name; reboot") }
        assertThrows(IllegalArgumentException::class.java) { RciCommands.setEnabled("Wireguard0", "$pub; reboot", true) }
        val unsafeOld = ConfiguredPeer("$oldPub; reboot", "anna", "10.8.0.5", 25, true, emptyList())
        assertThrows(IllegalArgumentException::class.java) { RciCommands.cutoverPeer("Wireguard0", unsafeOld, pub) }
        val unsafeSuffix = ConfiguredPeer(oldPub, "anna", "10.8.0.5", 25, true, listOf("connect; reboot"))
        assertThrows(IllegalArgumentException::class.java) { RciCommands.restorePeer("Wireguard0", unsafeSuffix) }
    }

    private fun parses(body: String): List<String> = Json.parseToJsonElement(body).jsonArray.mapNotNull {
        it.jsonObject["parse"]?.jsonPrimitive?.content
    }
}
