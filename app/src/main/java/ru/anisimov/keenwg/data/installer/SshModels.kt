package ru.anisimov.keenwg.data.installer

data class SshEndpoint(
    val host: String,
    val port: Int,
    val username: String,
) {
    init {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() })
        require(host.none { it in "/\\@" })
        require(port in 1..65535)
        require(username.matches(Regex("[A-Za-z0-9._-]{1,64}")))
    }
}

data class HostKeyObservation(
    val algorithm: String,
    val sha256: String,
) {
    init {
        require(algorithm.matches(Regex("[A-Za-z0-9@._+-]{1,64}")))
        require(sha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean = false,
)

class ValidatedTemporaryPath private constructor(val value: String) {
    companion object {
        private val ARCHIVE = Regex("/opt/tmp/keenwg-([0-9a-f]{32})\\.tar\\.gz")
        private val REQUEST = Regex("/opt/tmp/keenwg-([0-9a-f]{32})\\.json")

        fun archive(nonce: String) = parse("/opt/tmp/keenwg-${requireNonce(nonce)}.tar.gz")
        fun request(nonce: String) = parse("/opt/tmp/keenwg-${requireNonce(nonce)}.json")

        fun parse(value: String): ValidatedTemporaryPath {
            require(ARCHIVE.matches(value) || REQUEST.matches(value)) { "Unsupported temporary path" }
            return ValidatedTemporaryPath(value)
        }
    }
}

sealed class FixedCommand {
    abstract fun render(): String

    data object Probe : FixedCommand() {
        override fun render() = PROBE_COMMAND
    }

    data object CreateOwnerPairingOffer : FixedCommand() {
        override fun render() = "/opt/lib/keenwg-companion/current/keenwg-companion -config /opt/etc/keenwg/companion.json -create-pairing-offer owner"
    }

    class Install private constructor(private val nonce: String) : FixedCommand() {
        override fun render() = "umask 077; work=/opt/tmp/keenwg-$nonce; mkdir -m 700 \"\$work\"; " +
            "tar -xzf /opt/tmp/keenwg-$nonce.tar.gz -C \"\$work\"; " +
            "\"\$work/install-companion.sh\" --request /opt/tmp/keenwg-$nonce.json"

        companion object { fun create(nonce: String) = Install(requireNonce(nonce)) }
    }

    class Cleanup private constructor(private val nonce: String) : FixedCommand() {
        override fun render() = "rm -rf /opt/tmp/keenwg-$nonce /opt/tmp/keenwg-$nonce.tar.gz /opt/tmp/keenwg-$nonce.json"

        companion object { fun create(nonce: String) = Cleanup(requireNonce(nonce)) }
    }

    companion object {
        fun install(nonce: String): FixedCommand = Install.create(nonce)
        fun cleanup(nonce: String): FixedCommand = Cleanup.create(nonce)

        private const val PROBE_COMMAND =
            "printf 'architecture='; uname -m; " +
            "printf 'firmware='; firmware=\$(ndmc -c 'show version' 2>/dev/null | sed -n 's/^[[:space:]]*[Rr]elease[[:space:]]*:[[:space:]]*//p' | sed -n '1p'); " +
            "if test -z \"\$firmware\" && test -r /etc/version; then firmware=\$(sed -n '1p' /etc/version); fi; " +
            "if test -n \"\$firmware\"; then printf '%s\\n' \"\$firmware\"; else echo unknown; fi; " +
            "printf 'opt_free_kib='; df -Pk /opt | awk 'NR==2 {print \$4}'; " +
            "printf 'entware='; if test -d /opt/etc; then echo present; else echo missing; fi; " +
            "printf 'companion_config='; if test -f /opt/etc/keenwg/companion.json; then echo present; else echo missing; fi; " +
            "printf 'xkeen='; if test -x /opt/bin/xkeen; then /opt/bin/xkeen -version 2>/dev/null | sed -n '1p'; elif test -r /opt/etc/xray/version; then sed -n '1p' /opt/etc/xray/version; elif test -d /opt/etc/xray; then echo unknown; else echo missing; fi; " +
            "printf 'asc='; if command -v asc >/dev/null 2>&1; then echo present; else echo missing; fi; " +
            "printf 'xray='; if command -v xray >/dev/null 2>&1; then echo present; else echo missing; fi; " +
            "printf 'companion='; if test -x /opt/lib/keenwg-companion/current/keenwg-companion; then /opt/lib/keenwg-companion/current/keenwg-companion -version; else echo missing; fi"
    }
}

internal fun requireNonce(nonce: String): String {
    require(nonce.matches(Regex("[0-9a-f]{32}"))) { "Invalid install nonce" }
    return nonce
}

enum class SshErrorCode {
    HOST_KEY_UNAVAILABLE,
    HOST_KEY_MISMATCH,
    AUTHENTICATION_FAILED,
    CONNECTION_FAILED,
    COMMAND_TIMEOUT,
    UPLOAD_FAILED,
    OUTPUT_LIMIT,
}

class SshTransportException(val code: SshErrorCode, cause: Throwable? = null) :
    Exception("SSH transport failed: ${code.name.lowercase()}", cause)
