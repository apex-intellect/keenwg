package ru.anisimov.keenwg.ui.peers

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer

class PeerStatusPresentationTest {
    @Test fun `list distinguishes current recent disabled never and missing telemetry`() {
        assertEquals(PeerConnectionState.CONNECTED_NOW, peerConnectionState(peer(online = true)))
        assertEquals(PeerConnectionState.ACCESS_DISABLED, peerConnectionState(peer(enabled = false)))
        assertEquals(
            PeerConnectionState.RECENTLY_CONNECTED,
            peerConnectionState(peer(handshake = HandshakeStatus(HandshakeKind.AGE, 300))),
        )
        assertEquals(
            PeerConnectionState.NEVER_CONNECTED,
            peerConnectionState(peer(handshake = HandshakeStatus(HandshakeKind.NEVER))),
        )
        assertEquals(
            PeerConnectionState.NO_CONNECTION_DATA,
            peerConnectionState(peer(handshake = HandshakeStatus(HandshakeKind.INVALID))),
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
