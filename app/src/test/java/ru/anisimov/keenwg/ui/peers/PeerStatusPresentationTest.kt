package ru.anisimov.keenwg.ui.peers

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer

class PeerStatusPresentationTest {
    @Test fun `list distinguishes online disabled never and missing telemetry`() {
        assertEquals("Подключён", peerStatusLabel(peer(online = true)))
        assertEquals("Отключён", peerStatusLabel(peer(enabled = false)))
        assertEquals(
            "Подключений пока не было",
            peerStatusLabel(peer(handshake = HandshakeStatus(HandshakeKind.NEVER))),
        )
        assertEquals(
            "Нет данных о последнем подключении",
            peerStatusLabel(peer(handshake = HandshakeStatus(HandshakeKind.INVALID))),
        )
    }

    private fun peer(
        online: Boolean = false,
        enabled: Boolean = true,
        handshake: HandshakeStatus = HandshakeStatus(HandshakeKind.JUST_NOW, 0),
    ) = Peer(
        publicKey = "key",
        name = "phone",
        ip = "10.8.0.3",
        online = online,
        handshake = handshake,
        clientUploadBytes = 0,
        clientDownloadBytes = 0,
        enabled = enabled,
    )
}
