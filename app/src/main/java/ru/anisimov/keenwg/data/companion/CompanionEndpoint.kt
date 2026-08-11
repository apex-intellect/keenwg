package ru.anisimov.keenwg.data.companion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile

data class CompanionTarget(
    val baseUrl: HttpUrl,
    val certificatePin: String,
)

data class CompanionEndpoint(
    val baseUrl: HttpUrl,
    val certificatePin: String,
    val deviceToken: String,
) {
    init {
        require(deviceToken.isNotBlank() && deviceToken.length <= 512 && deviceToken.none(Char::isISOControl))
    }

    val target: CompanionTarget get() = CompanionTarget(baseUrl, certificatePin)
}

fun RouterProfile.requireCompanionTarget(): CompanionTarget {
    val url = companionUrl.toHttpUrlOrNull() ?: throw CompanionEndpointException()
    if (url.scheme != "https" || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
        url.query != null || url.fragment != null || (url.encodedPath != "/" && url.encodedPath.isNotEmpty()) ||
        certificatePin.isBlank()
    ) throw CompanionEndpointException()
    return CompanionTarget(url, certificatePin)
}

fun ActiveRouterProfile.requireCompanionEndpoint(): CompanionEndpoint = CompanionEndpoint(
    baseUrl = profile.requireCompanionTarget().baseUrl,
    certificatePin = profile.certificatePin,
    deviceToken = secrets.companionToken,
)

class CompanionEndpointException : IllegalArgumentException("Companion не настроен")
