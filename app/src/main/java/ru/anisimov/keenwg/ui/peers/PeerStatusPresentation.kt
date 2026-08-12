package ru.anisimov.keenwg.ui.peers

import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.Peer

internal enum class PeerConnectionState {
    CONNECTED_NOW,
    RECENTLY_CONNECTED,
    ACCESS_DISABLED,
    NEVER_CONNECTED,
    NO_CONNECTION_DATA,
}

internal fun peerConnectionState(peer: Peer): PeerConnectionState = when {
    !peer.enabled -> PeerConnectionState.ACCESS_DISABLED
    peer.online -> PeerConnectionState.CONNECTED_NOW
    peer.handshake.kind == HandshakeKind.AGE || peer.handshake.kind == HandshakeKind.JUST_NOW ->
        PeerConnectionState.RECENTLY_CONNECTED
    peer.handshake.kind == HandshakeKind.NEVER -> PeerConnectionState.NEVER_CONNECTED
    else -> PeerConnectionState.NO_CONNECTION_DATA
}
