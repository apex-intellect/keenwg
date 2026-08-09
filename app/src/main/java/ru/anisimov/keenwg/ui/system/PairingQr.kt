package ru.anisimov.keenwg.ui.system

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.PairingOffer
import ru.anisimov.keenwg.domain.model.RouterProfile

@Serializable
private data class PairingQrPayload(
    @SerialName("schema") val schema: Int = 1,
    val url: String,
    val pin: String,
    @SerialName("offer_id") val offerId: String,
    val secret: String,
    val scope: String,
    @SerialName("expires_at") val expiresAt: String,
)

private val compactJson = Json { encodeDefaults = true; explicitNulls = false }

internal fun pairingQrPayload(profile: RouterProfile, offer: PairingOffer): String {
    require(profile.companionUrl.startsWith("https://") && profile.certificatePin.startsWith("sha256/"))
    require(offer.offerId.isNotBlank() && offer.secret.isNotBlank() && offer.expiresAt.isNotBlank())
    return compactJson.encodeToString(
        PairingQrPayload(
            url = profile.companionUrl,
            pin = profile.certificatePin,
            offerId = offer.offerId,
            secret = offer.secret,
            scope = offer.scope.name.lowercase(),
            expiresAt = offer.expiresAt,
        ),
    )
}
