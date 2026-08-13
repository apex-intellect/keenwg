package ru.anisimov.keenwg.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeNormalizer
import ru.anisimov.keenwg.domain.model.HandshakeStatus

class HandshakeNormalizerTest {
    private data class Case(
        val online: Boolean,
        val raw: Long?,
        val rx: Long,
        val tx: Long,
        val want: HandshakeStatus,
    )

    @Test fun `normalizes KeenOS ages and sentinels`() {
        val cases = listOf(
            Case(true, 0, 0, 0, HandshakeStatus(HandshakeKind.JUST_NOW, 0)),
            Case(false, 0, 0, 0, HandshakeStatus(HandshakeKind.NEVER)),
            Case(false, 0, 42, 0, HandshakeStatus(HandshakeKind.UNKNOWN)),
            Case(false, 117, 42, 9, HandshakeStatus(HandshakeKind.AGE, 117)),
            Case(false, 1_762_810_134, 0, 0, HandshakeStatus(HandshakeKind.INVALID)),
            Case(false, Int.MAX_VALUE.toLong(), 0, 0, HandshakeStatus(HandshakeKind.INVALID)),
            Case(false, -1, 0, 0, HandshakeStatus(HandshakeKind.INVALID)),
        )

        cases.forEach { c ->
            assertEquals(c.want, HandshakeNormalizer.normalize(c.online, c.raw, c.rx, c.tx))
        }
    }

}
