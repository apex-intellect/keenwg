package ru.anisimov.keenwg.data.xkeen

import java.util.UUID
import kotlinx.coroutines.delay
import ru.anisimov.keenwg.data.companion.CompanionEndpoint

interface XkeenRepositoryGateway {
    suspend fun probe(endpoint: CompanionEndpoint): XkeenStatus
    suspend fun status(endpoint: CompanionEndpoint): XkeenStatus
    suspend fun refreshAndAwait(endpoint: CompanionEndpoint, stateVersion: Long): XkeenOperation
    suspend fun selectAndAwait(endpoint: CompanionEndpoint, nodeId: String, stateVersion: Long): XkeenOperation
    suspend fun diagnostics(endpoint: CompanionEndpoint): XkeenDiagnosticReport =
        throw UnsupportedOperationException("Diagnostics are unavailable")
}

class XkeenRepository(
    private val gateway: XkeenGateway,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val maxPolls: Int = 30,
) : XkeenRepositoryGateway {
    init {
        require(maxPolls > 0)
    }

    override suspend fun probe(endpoint: CompanionEndpoint): XkeenStatus = gateway.probe(endpoint)

    override suspend fun status(endpoint: CompanionEndpoint): XkeenStatus = gateway.status(endpoint)

    override suspend fun diagnostics(endpoint: CompanionEndpoint): XkeenDiagnosticReport = gateway.diagnostics(endpoint)

    override suspend fun refreshAndAwait(endpoint: CompanionEndpoint, stateVersion: Long): XkeenOperation {
        val key = idGenerator()
        val submitted = submitOnce { gateway.refresh(endpoint, stateVersion, key) }
        return submitted?.takeIf { it.state == XkeenOperationState.TERMINAL }
            ?: poll(endpoint, key)
    }

    override suspend fun selectAndAwait(
        endpoint: CompanionEndpoint,
        nodeId: String,
        stateVersion: Long,
    ): XkeenOperation {
        val key = idGenerator()
        val submitted = submitOnce { gateway.select(endpoint, nodeId, stateVersion, key) }
        return submitted?.takeIf { it.state == XkeenOperationState.TERMINAL }
            ?: poll(endpoint, key)
    }

    private suspend fun submitOnce(block: suspend () -> XkeenOperation): XkeenOperation? = try {
        block()
    } catch (failure: XkeenException) {
        if (failure.code !in TRANSIENT_CODES) throw failure
        null
    }

    private suspend fun poll(endpoint: CompanionEndpoint, key: String): XkeenOperation {
        repeat(maxPolls) { index ->
            val operation = try {
                gateway.operation(endpoint, key)
            } catch (failure: XkeenException) {
                if (failure.code !in TRANSIENT_CODES) throw failure
                null
            }
            if (operation?.state == XkeenOperationState.TERMINAL) return operation
            if (index + 1 < maxPolls) delayMillis(POLL_INTERVAL_MILLIS)
        }
        throw XkeenException(
            XkeenErrorCode.OPERATION_TIMEOUT,
            "Операция продолжается; обновите статус",
        )
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        val TRANSIENT_CODES = setOf(XkeenErrorCode.NETWORK, XkeenErrorCode.TIMEOUT)
    }
}
