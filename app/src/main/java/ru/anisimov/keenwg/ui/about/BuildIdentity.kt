package ru.anisimov.keenwg.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import ru.anisimov.keenwg.BuildConfig

enum class BuildProvenance {
    OFFICIAL,
    UNVERIFIED,
}

fun classifyBuildProvenance(
    certificates: List<ByteArray>,
    expectedSha256: String,
): BuildProvenance {
    val normalizedExpected = expectedSha256.trim().lowercase()
    if (!normalizedExpected.matches(Regex("[0-9a-f]{64}")) || certificates.size != 1) {
        return BuildProvenance.UNVERIFIED
    }
    val actual = MessageDigest.getInstance("SHA-256")
        .digest(certificates.single())
        .joinToString("") { "%02x".format(it) }
    return if (actual == normalizedExpected) BuildProvenance.OFFICIAL else BuildProvenance.UNVERIFIED
}

@Suppress("DEPRECATION")
fun currentBuildProvenance(context: Context): BuildProvenance {
    val certificates = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            packageInfo.signatures.orEmpty().map { it.toByteArray() }
        }
    }.getOrDefault(emptyList())
    return classifyBuildProvenance(certificates, BuildConfig.OFFICIAL_SIGNER_SHA256)
}
