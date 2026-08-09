package ru.anisimov.keenwg.domain.model

enum class HandshakeKind { JUST_NOW, AGE, NEVER, UNKNOWN, INVALID }

data class HandshakeStatus(
    val kind: HandshakeKind,
    val ageSeconds: Long? = null,
)

object HandshakeNormalizer {
    fun normalize(
        online: Boolean,
        raw: Long?,
        routerRxBytes: Long,
        routerTxBytes: Long,
    ): HandshakeStatus = when {
        raw == null -> HandshakeStatus(HandshakeKind.UNKNOWN)
        raw < 0L || raw >= 1_000_000_000L -> HandshakeStatus(HandshakeKind.INVALID)
        raw == 0L && online -> HandshakeStatus(HandshakeKind.JUST_NOW, 0L)
        raw == 0L && routerRxBytes == 0L && routerTxBytes == 0L -> HandshakeStatus(HandshakeKind.NEVER)
        raw == 0L -> HandshakeStatus(HandshakeKind.UNKNOWN)
        else -> HandshakeStatus(HandshakeKind.AGE, raw)
    }
}
