package ru.anisimov.keenwg.data.xkeen

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings

class XkeenRepositoryTest {
    @Test fun `lost select response polls same operation without resubmitting`() = runTest {
        val gateway = FakeGateway(
            selectFailure = XkeenException(XkeenErrorCode.NETWORK, "Связь прервана"),
            polled = ArrayDeque(listOf(operation(XkeenOperationState.RUNNING), successOperation())),
        )
        val repository = repository(gateway)

        val result = repository.selectAndAwait(settings(), NODE_ID, 7)

        assertEquals(XkeenOperationResult.SUCCESS, result.result)
        assertEquals(1, gateway.selectCalls)
        assertEquals(listOf(KEY, KEY), gateway.operationKeys)
    }

    @Test fun `lost refresh response polls same operation without resubmitting`() = runTest {
        val gateway = FakeGateway(
            refreshFailure = XkeenException(XkeenErrorCode.TIMEOUT, "Тайм-аут"),
            polled = ArrayDeque(listOf(successOperation())),
        )

        val result = repository(gateway).refreshAndAwait(settings(), 7)

        assertEquals(XkeenOperationResult.SUCCESS, result.result)
        assertEquals(1, gateway.refreshCalls)
        assertEquals(listOf(KEY), gateway.operationKeys)
    }

    @Test fun `terminal submission returns without polling`() = runTest {
        val gateway = FakeGateway(selected = successOperation())

        val result = repository(gateway).selectAndAwait(settings(), NODE_ID, 7)

        assertEquals(XkeenOperationResult.SUCCESS, result.result)
        assertEquals(1, gateway.selectCalls)
        assertEquals(emptyList<String>(), gateway.operationKeys)
    }

    @Test fun `proven rejected submission is not polled`() = runTest {
        val gateway = FakeGateway(selectFailure = XkeenException(XkeenErrorCode.UNAUTHORIZED, "Нет доступа"))

        val failure = try {
            repository(gateway).selectAndAwait(settings(), NODE_ID, 7)
            fail("Expected XkeenException")
            error("unreachable")
        } catch (failure: XkeenException) {
            failure
        }

        assertEquals(XkeenErrorCode.UNAUTHORIZED, failure.code)
        assertEquals(1, gateway.selectCalls)
        assertEquals(emptyList<String>(), gateway.operationKeys)
    }

    @Test fun `non terminal operation is bounded`() = runTest {
        val gateway = FakeGateway(
            selected = operation(XkeenOperationState.QUEUED),
            defaultPolled = operation(XkeenOperationState.RUNNING),
        )
        var delays = 0
        val repository = XkeenRepository(gateway, { KEY }, { delays++ }, maxPolls = 3)

        val failure = try {
            repository.selectAndAwait(settings(), NODE_ID, 7)
            fail("Expected XkeenException")
            error("unreachable")
        } catch (failure: XkeenException) {
            failure
        }

        assertEquals(XkeenErrorCode.OPERATION_TIMEOUT, failure.code)
        assertEquals(3, gateway.operationKeys.size)
        assertEquals(2, delays)
    }

    private fun repository(gateway: FakeGateway) = XkeenRepository(gateway, { KEY }, {})

    private class FakeGateway(
        private val selected: XkeenOperation = operation(XkeenOperationState.QUEUED),
        private val refreshed: XkeenOperation = operation(XkeenOperationState.QUEUED, kind = "refresh"),
        private val selectFailure: XkeenException? = null,
        private val refreshFailure: XkeenException? = null,
        private val polled: ArrayDeque<XkeenOperation> = ArrayDeque(),
        private val defaultPolled: XkeenOperation = successOperation(),
    ) : XkeenGateway {
        var selectCalls = 0
        var refreshCalls = 0
        val operationKeys = mutableListOf<String>()

        override suspend fun probe(settings: ServerSettings) = status()
        override suspend fun status(settings: ServerSettings) = status()

        override suspend fun refresh(settings: ServerSettings, stateVersion: Long, idempotencyKey: String): XkeenOperation {
            refreshCalls++
            refreshFailure?.let { throw it }
            return refreshed
        }

        override suspend fun select(settings: ServerSettings, nodeId: String, stateVersion: Long, idempotencyKey: String): XkeenOperation {
            selectCalls++
            selectFailure?.let { throw it }
            return selected
        }

        override suspend fun operation(settings: ServerSettings, idempotencyKey: String): XkeenOperation {
            operationKeys += idempotencyKey
            return if (polled.isEmpty()) defaultPolled else polled.removeFirst()
        }
    }

    private companion object {
        const val KEY = "11111111-1111-4111-8111-111111111111"
        const val NODE_ID = "aabbccddeeff00112233445566778899"

        fun settings() = ServerSettings(xkeenControllerToken = "token")
        fun status() = XkeenStatus("0.4.0", 7, subscription = XkeenSubscription(1, false, emptyList()))
        fun operation(state: XkeenOperationState, kind: String = "select") = XkeenOperation(
            KEY, kind, state, startedAt = 1,
        )
        fun successOperation() = XkeenOperation(
            KEY, "select", XkeenOperationState.TERMINAL, XkeenOperationResult.SUCCESS, startedAt = 1, finishedAt = 2,
        )
    }
}
