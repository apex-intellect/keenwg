package ru.anisimov.keenwg.data.xkeen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CompanionEndpoint

class XkeenRuntimeRepositoryTest {
    private val endpoint = CompanionEndpoint("https://router.example/".toHttpUrl(), "pin", "token")

    @Test fun `load includes owner access and does not infer owner from status`() = runTest {
        val gateway = RuntimeFake().apply { access = CapabilityAccess.READ }
        val snapshot = XkeenRuntimeRepository(gateway).load(endpoint)
        assertTrue(snapshot.running)
        assertFalse(snapshot.canControl)
    }

    @Test fun `POST response is not trusted and GET actual state wins`() = runTest {
        val gateway = RuntimeFake()
        val result = XkeenRuntimeRepository(gateway).setEnabled(endpoint, false)
        assertEquals(listOf("POST:false", "GET"), gateway.calls)
        assertEquals(true, result.running)
        assertEquals(XkeenRuntimeError.VERIFICATION_FAILED, result.error)
    }

    @Test fun `lost POST response retains warning while independent GET recovers actual state`() = runTest {
        val gateway = RuntimeFake().apply { running = false; postFailure = XkeenRuntimeException(XkeenRuntimeError.UNAVAILABLE) }
        val result = XkeenRuntimeRepository(gateway).setEnabled(endpoint, false)
        assertEquals(false, result.running)
        assertEquals(XkeenRuntimeError.UNAVAILABLE, result.error)
        assertEquals(listOf("POST:false", "GET"), gateway.calls)
    }

    @Test fun `failed read back becomes unknown never optimistic off`() = runTest {
        val gateway = RuntimeFake().apply { getFailure = XkeenRuntimeException(XkeenRuntimeError.UNAVAILABLE) }
        val result = XkeenRuntimeRepository(gateway).setEnabled(endpoint, false)
        assertNull(result.running)
        assertEquals(XkeenRuntimeError.UNAVAILABLE, result.error)
    }

    @Test fun `forbidden is retained even when someone else changes state`() = runTest {
        val gateway = RuntimeFake().apply { running = false; postFailure = XkeenRuntimeException(XkeenRuntimeError.FORBIDDEN) }
        val result = XkeenRuntimeRepository(gateway).setEnabled(endpoint, false)
        assertEquals(false, result.running)
        assertEquals(XkeenRuntimeError.FORBIDDEN, result.error)
    }

    @Test fun `backend verification failure is retained even when process reports desired state`() = runTest {
        val gateway = RuntimeFake().apply {
            running = true
            postFailure = XkeenRuntimeException(XkeenRuntimeError.VERIFICATION_FAILED)
        }
        val result = XkeenRuntimeRepository(gateway).setEnabled(endpoint, true)
        assertEquals(true, result.running)
        assertEquals(XkeenRuntimeError.VERIFICATION_FAILED, result.error)
        assertEquals(listOf("POST:true", "GET"), gateway.calls)
    }

    @Test fun `cancellation is propagated without post cancellation request`() = runTest {
        val gateway = RuntimeFake().apply { postFailure = CancellationException() }
        try {
            XkeenRuntimeRepository(gateway).setEnabled(endpoint, false)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(listOf("POST:false"), gateway.calls)
        }
    }

    private class RuntimeFake : XkeenRuntimeGateway {
        var running = true
        var access = CapabilityAccess.WRITE
        var postFailure: Exception? = null
        var getFailure: Exception? = null
        val calls = mutableListOf<String>()
        override suspend fun status(endpoint: CompanionEndpoint): XkeenRuntimeStatus {
            calls += "GET"
            getFailure?.let { throw it }
            return XkeenRuntimeStatus(1, running, true)
        }
        override suspend fun access(endpoint: CompanionEndpoint) = access
        override suspend fun setEnabled(endpoint: CompanionEndpoint, enabled: Boolean): XkeenRuntimeStatus {
            calls += "POST:$enabled"
            postFailure?.let { throw it }
            return XkeenRuntimeStatus(1, enabled, true)
        }
    }
}
