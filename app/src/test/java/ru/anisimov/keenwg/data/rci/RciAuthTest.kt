package ru.anisimov.keenwg.data.rci

import org.junit.Assert.assertEquals
import org.junit.Test

class RciAuthTest {
    // Vectors computed independently (login=admin, realm=test-realm, password=secret, challenge=CHALLENGE123).
    @Test fun ha1_matches() {
        assertEquals("31741de2567d5bec5700aa72b24530f1", RciAuth.md5Hex("admin:test-realm:secret"))
    }

    @Test fun authResponse_matches() {
        assertEquals(
            "26d4546b6381a1300cfee2b219c8f99f30b014a599e766e3aa7315ee1eba67d2",
            RciAuth.authResponse("admin", "test-realm", "secret", "CHALLENGE123"),
        )
    }
}
