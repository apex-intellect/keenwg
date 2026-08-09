package ru.anisimov.keenwg.domain.model

data class Peer(
    val publicKey: String,
    val name: String,
    val ip: String?,
    val online: Boolean,
    val handshake: HandshakeStatus,
    val clientUploadBytes: Long,
    val clientDownloadBytes: Long,
    val enabled: Boolean,
)
