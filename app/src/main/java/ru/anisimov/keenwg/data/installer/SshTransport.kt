package ru.anisimov.keenwg.data.installer

import java.io.Closeable

interface SshTransport {
    suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation
    suspend fun connect(
        endpoint: SshEndpoint,
        password: ByteArray,
        expectedHostKey: HostKeyObservation,
    ): SshSession
}

interface SshSession : Closeable {
    suspend fun exec(command: FixedCommand): CommandResult
    suspend fun upload(bytes: ByteArray, remotePath: ValidatedTemporaryPath)
}

internal fun interface HostKeyObserver {
    fun observe(endpoint: SshEndpoint, timeoutMillis: Int): HostKeyObservation
}

internal fun interface SshSessionConnector {
    fun connect(
        endpoint: SshEndpoint,
        password: ByteArray,
        expectedHostKey: HostKeyObservation,
        timeoutMillis: Int,
    ): SshSession
}
