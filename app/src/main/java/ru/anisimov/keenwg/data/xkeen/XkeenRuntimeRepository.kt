package ru.anisimov.keenwg.data.xkeen

import kotlinx.coroutines.CancellationException
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CompanionEndpoint

data class XkeenRuntimeSnapshot(val running: Boolean, val canControl: Boolean)
data class XkeenRuntimeChange(val running: Boolean?, val error: XkeenRuntimeError? = null)

class XkeenRuntimeRepository(private val gateway: XkeenRuntimeGateway) {
    suspend fun load(endpoint: CompanionEndpoint): XkeenRuntimeSnapshot {
        val status = gateway.status(endpoint)
        val access = gateway.access(endpoint)
        if (access == CapabilityAccess.NONE) throw XkeenRuntimeException(XkeenRuntimeError.FORBIDDEN)
        return XkeenRuntimeSnapshot(status.running, access == CapabilityAccess.WRITE)
    }

    suspend fun setEnabled(endpoint: CompanionEndpoint, enabled: Boolean): XkeenRuntimeChange {
        val operationError = try {
            gateway.setEnabled(endpoint, enabled)
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            failure.runtimeError()
        }
        // A lost POST response is not evidence of failure or success. Always read back the router.
        val actual = try {
            gateway.status(endpoint).running
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return XkeenRuntimeChange(null, operationError ?: failure.runtimeError())
        }
        // A process-state GET cannot undo a failed startup/firewall verification or
        // prove that a lost POST response was successful. Keep the operation warning.
        val error = operationError ?: if (actual == enabled) null else XkeenRuntimeError.VERIFICATION_FAILED
        return XkeenRuntimeChange(actual, error)
    }
}

internal fun Exception.runtimeError(): XkeenRuntimeError =
    (this as? XkeenRuntimeException)?.code ?: XkeenRuntimeError.UNAVAILABLE
