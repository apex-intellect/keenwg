package ru.anisimov.keenwg.data.xkeen

import java.util.UUID
import kotlinx.coroutines.delay
import ru.anisimov.keenwg.domain.model.ServerSettings

interface XkeenRepositoryGateway {
    suspend fun probe(settings: ServerSettings): XkeenStatus
    suspend fun status(settings: ServerSettings): XkeenStatus
    suspend fun refreshAndAwait(settings: ServerSettings, stateVersion: Long): XkeenOperation
    suspend fun selectAndAwait(settings: ServerSettings, nodeId: String, stateVersion: Long): XkeenOperation
    suspend fun diagnostics(settings: ServerSettings): XkeenDiagnosticReport =
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

    override suspend fun probe(settings: ServerSettings): XkeenStatus = gateway.probe(settings)

    override suspend fun status(settings: ServerSettings): XkeenStatus = gateway.status(settings)

    override suspend fun diagnostics(settings: ServerSettings): XkeenDiagnosticReport = gateway.diagnostics(settings)

    override suspend fun refreshAndAwait(settings: ServerSettings, stateVersion: Long): XkeenOperation {
        val key = idGenerator()
        val submitted = submitOnce { gateway.refresh(settings, stateVersion, key) }
        return submitted?.takeIf { it.state == XkeenOperationState.TERMINAL }
            ?: poll(settings, key)
    }

    override suspend fun selectAndAwait(
        settings: ServerSettings,
        nodeId: String,
        stateVersion: Long,
    ): XkeenOperation {
        val key = idGenerator()
        val submitted = submitOnce { gateway.select(settings, nodeId, stateVersion, key) }
        return submitted?.takeIf { it.state == XkeenOperationState.TERMINAL }
            ?: poll(settings, key)
    }

    private suspend fun submitOnce(block: suspend () -> XkeenOperation): XkeenOperation? = try {
        block()
    } catch (failure: XkeenException) {
        if (failure.code !in TRANSIENT_CODES) throw failure
        null
    }

    private suspend fun poll(settings: ServerSettings, key: String): XkeenOperation {
        repeat(maxPolls) { index ->
            val operation = try {
                gateway.operation(settings, key)
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
