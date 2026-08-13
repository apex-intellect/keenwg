package ru.anisimov.keenwg.data.routes

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioPayloadTest {
    @Test fun `legacy companion null condition arrays decode as empty lists`() {
        val payload = """{
          "schema_version":1,
          "state_version":7,
          "modules":{"devices":false,"services":false,"domains":true,"ip":true},
          "presets":[{
            "id":"russia-direct",
            "label":"Russia direct",
            "optional":true,
            "conditions":{"device_ids":null,"services":null,"domains":null,"suffixes":["ru"],"geosites":null,"cidrs":null},
            "outcome":{"mode":"direct"}
          }]
        }"""

        val catalog = scenarioWireJson.decodeFromString<ScenarioCatalog>(payload)

        val conditions = catalog.presets.single().conditions
        assertTrue(conditions.deviceIds.isEmpty())
        assertTrue(conditions.services.isEmpty())
        assertTrue(conditions.domains.isEmpty())
        assertTrue(conditions.geosites.isEmpty())
        assertTrue(conditions.cidrs.isEmpty())
    }
}
