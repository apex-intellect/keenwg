package ru.anisimov.keenwg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpAllocatorTest {
    @Test fun picks_lowest_free() {
        assertEquals(
            "10.8.0.2",
            IpAllocator.nextFreeIp("10.8.0.", setOf("10.8.0.3", "10.8.0.4", "10.8.0.5", "10.8.0.6")),
        )
    }

    @Test fun skips_taken_and_reserved() {
        assertEquals(
            "10.8.0.7",
            IpAllocator.nextFreeIp("10.8.0.", setOf("10.8.0.2", "10.8.0.3", "10.8.0.4", "10.8.0.5", "10.8.0.6")),
        )
    }

    @Test fun returns_null_when_full() {
        val all = (2..254).map { "10.8.0.$it" }.toSet()
        assertNull(IpAllocator.nextFreeIp("10.8.0.", all))
    }
}
