package ru.anisimov.keenwg.data.installer

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JschSshTransport internal constructor(
    private val observer: HostKeyObserver = RealHostKeyObserver(),
    private val connector: SshSessionConnector = RealSshSessionConnector(),
    private val connectTimeoutMillis: Int = 8_000,
) : SshTransport {
    override suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation = withContext(Dispatchers.IO) {
        try {
            observer.observe(endpoint, connectTimeoutMillis)
        } catch (failure: SshTransportException) {
            throw failure
        } catch (failure: Exception) {
            throw SshTransportException(SshErrorCode.HOST_KEY_UNAVAILABLE, failure)
        }
    }

    override suspend fun connect(
        endpoint: SshEndpoint,
        password: ByteArray,
        expectedHostKey: HostKeyObservation,
    ): SshSession = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "SSH password is empty" }
        try {
            connector.connect(endpoint, password, expectedHostKey, connectTimeoutMillis)
        } catch (failure: SshTransportException) {
            throw failure
        } catch (failure: Exception) {
            throw SshTransportException(SshErrorCode.AUTHENTICATION_FAILED, failure)
        } finally {
            password.fill(0)
        }
    }
}

private class RealHostKeyObserver : HostKeyObserver {
    override fun observe(endpoint: SshEndpoint, timeoutMillis: Int): HostKeyObservation {
        val repository = CapturingHostKeyRepository()
        val session = JSch().getSession(endpoint.username, endpoint.host, endpoint.port)
        session.setHostKeyRepository(repository)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "none")
        try {
            session.connect(timeoutMillis)
        } catch (_: Exception) {
            return repository.observation ?: throw SshTransportException(SshErrorCode.HOST_KEY_UNAVAILABLE)
        } finally {
            session.disconnect()
        }
        throw SshTransportException(SshErrorCode.HOST_KEY_UNAVAILABLE)
    }
}

private class RealSshSessionConnector : SshSessionConnector {
    override fun connect(
        endpoint: SshEndpoint,
        password: ByteArray,
        expectedHostKey: HostKeyObservation,
        timeoutMillis: Int,
    ): SshSession {
        val session = JSch().getSession(endpoint.username, endpoint.host, endpoint.port)
        try {
            session.setHostKeyRepository(ExactHostKeyRepository(expectedHostKey))
            session.setConfig(Properties().apply {
                setProperty("StrictHostKeyChecking", "yes")
                setProperty("PreferredAuthentications", "password")
                setProperty("PasswordAuthentication", "yes")
            })
            session.setPassword(password)
            session.connect(timeoutMillis)
            session.timeout = OPERATION_TIMEOUT_MILLIS
            session.setServerAliveInterval(5_000)
            session.setServerAliveCountMax(2)
            return JschSshSession(session)
        } catch (failure: Exception) {
            session.disconnect()
            throw failure
        }
    }
}

internal fun interface ExecChannelFactory {
    fun open(session: Session): ChannelExec
}

internal class JschExecUploader(
    private val channels: ExecChannelFactory = ExecChannelFactory { session ->
        session.openChannel("exec") as ChannelExec
    },
) {
    fun upload(session: Session, bytes: ByteArray, remotePath: ValidatedTemporaryPath) {
        val stdout = BoundedByteCollector(MAX_OUTPUT_BYTES)
        val stderr = BoundedByteCollector(MAX_OUTPUT_BYTES)
        val channel = channels.open(session)
        try {
            ByteArrayInputStream(bytes).use { input ->
                channel.setPty(false)
                channel.setInputStream(input, false)
                channel.setOutputStream(stdout, false)
                channel.setErrStream(stderr, false)
                channel.setCommand("umask 077; cat > ${remotePath.value}")
                channel.connect(CHANNEL_CONNECT_TIMEOUT_MILLIS)
                val deadline = System.nanoTime() + OPERATION_TIMEOUT_MILLIS * 1_000_000L
                while (!channel.isClosed) {
                    if (System.nanoTime() >= deadline) throw SshTransportException(SshErrorCode.COMMAND_TIMEOUT)
                    Thread.sleep(20)
                }
            }
            if (channel.exitStatus != 0 || stdout.truncated || stderr.truncated) {
                throw SshTransportException(SshErrorCode.UPLOAD_FAILED)
            }
        } finally {
            channel.disconnect()
        }
    }
}

private class JschSshSession(
    private val session: Session,
) : SshSession {
    override suspend fun exec(command: FixedCommand): CommandResult = withContext(Dispatchers.IO) {
        check(session.isConnected) { "SSH session is closed" }
        val stdout = BoundedByteCollector(MAX_OUTPUT_BYTES)
        val stderr = BoundedByteCollector(MAX_OUTPUT_BYTES)
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setPty(false)
            channel.setInputStream(null)
            channel.setOutputStream(stdout, false)
            channel.setErrStream(stderr, false)
            channel.setCommand(command.render())
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MILLIS)
            val deadline = System.nanoTime() + OPERATION_TIMEOUT_MILLIS * 1_000_000L
            while (!channel.isClosed) {
                if (System.nanoTime() >= deadline) throw SshTransportException(SshErrorCode.COMMAND_TIMEOUT)
                Thread.sleep(20)
            }
            CommandResult(
                exitCode = channel.exitStatus,
                stdout = stdout.text(),
                stderr = stderr.text(),
                outputTruncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            channel.disconnect()
        }
    }

    override suspend fun upload(bytes: ByteArray, remotePath: ValidatedTemporaryPath) = withContext(Dispatchers.IO) {
        check(session.isConnected) { "SSH session is closed" }
        require(bytes.size in 1..MAX_UPLOAD_BYTES) { "Upload size is invalid" }
        try {
            JschExecUploader().upload(session, bytes, remotePath)
        } catch (failure: SshTransportException) {
            throw failure
        } catch (failure: Exception) {
            throw SshTransportException(SshErrorCode.UPLOAD_FAILED, failure)
        }
    }

    override fun close() {
        session.disconnect()
    }
}

internal class BoundedByteCollector(private val limit: Int) : OutputStream() {
    private val body = ByteArray(limit)
    private var size = 0
    @Volatile var truncated: Boolean = false
        private set

    init { require(limit > 0) }

    @Synchronized override fun write(value: Int) {
        if (size < limit) body[size++] = value.toByte() else truncated = true
    }

    @Synchronized override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        val count = minOf(length, limit - size)
        if (count > 0) {
            source.copyInto(body, size, offset, offset + count)
            size += count
        }
        if (count < length) truncated = true
    }

    @Synchronized fun bytes(): ByteArray = body.copyOf(size)
    @Synchronized fun text(): String = bytes().toString(Charsets.UTF_8)
}

private const val CHANNEL_CONNECT_TIMEOUT_MILLIS = 8_000
private const val OPERATION_TIMEOUT_MILLIS = 60_000
private const val MAX_OUTPUT_BYTES = 65_536
private const val MAX_UPLOAD_BYTES = 16 * 1024 * 1024
