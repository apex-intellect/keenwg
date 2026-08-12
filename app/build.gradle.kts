import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.anisimov.keenwg"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.anisimov.keenwg"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "2.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val signingValues = listOf(
                System.getenv("KEENWG_KEYSTORE_FILE"),
                System.getenv("KEENWG_KEYSTORE_PASSWORD"),
                System.getenv("KEENWG_KEY_ALIAS"),
                System.getenv("KEENWG_KEY_PASSWORD"),
            )
            if (signingValues.all { !it.isNullOrBlank() }) {
                signingConfig = signingConfigs.create("externalRelease") {
                    storeFile = file(requireNotNull(signingValues[0]))
                    storePassword = signingValues[1]
                    keyAlias = signingValues[2]
                    keyPassword = signingValues[3]
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                    enableV4Signing = false
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Keep the already-compressed companion bundle byte-for-byte stable in the APK.
        // The .tgz suffix also avoids AAPT's special .gz expansion and suffix removal.
        noCompress += "tgz"
    }
}

val verifyCompanionAsset by tasks.registering {
    group = "verification"
    description = "Verifies the release companion asset against its committed manifest."
    doLast {
        val directory = layout.projectDirectory.dir("src/main/assets/companion").asFile
        val manifestFile = directory.resolve("manifest.json")
        check(manifestFile.isFile) { "Companion manifest is missing" }
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any>
        check((manifest["schema_version"] as Number).toInt() == 1) { "Unsupported companion manifest schema" }
        check(manifest["architecture"] == "arm64") { "Release companion must be ARM64" }
        val assetName = manifest["asset"] as? String ?: error("Companion asset name is missing")
        check(assetName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid companion asset name" }
        val asset = directory.resolve(assetName)
        check(asset.isFile) { "Companion asset is missing: $assetName" }
        val expectedSize = (manifest["size"] as Number).toLong()
        check(asset.length() == expectedSize) { "Companion asset size does not match manifest" }
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualHash == manifest["sha256"]) { "Companion asset SHA-256 does not match manifest" }
    }
}

val verifyFileProviderPolicy by tasks.registering {
    group = "verification"
    description = "Verifies that support exports use a non-exported, cache-only FileProvider path."
    val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    val pathsFile = layout.projectDirectory.file("src/main/res/xml/file_paths.xml")
    inputs.files(manifestFile, pathsFile)
    doLast {
        val manifest = manifestFile.asFile.readText()
        val paths = pathsFile.asFile.readText()
        check(manifest.contains("androidx.core.content.FileProvider")) { "FileProvider is missing" }
        check(manifest.contains("android:authorities=\"\${applicationId}.fileprovider\"")) { "FileProvider authority is not application-scoped" }
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        check(provider.contains("android:exported=\"false\"")) { "FileProvider must not be exported" }
        check(provider.contains("android:grantUriPermissions=\"true\"")) { "Temporary URI grants are required" }
        check(paths.contains("<cache-path name=\"support\" path=\"support/\"")) { "Support cache path is missing" }
        check(paths.contains("<cache-path name=\"backup\" path=\"backup/\"")) { "Backup cache path is missing" }
        check(!paths.contains("<root-path") && !paths.contains("<external-path") && !paths.contains("path=\".\"")) {
            "FileProvider exposes a broad path"
        }
    }
}

val verifyLocaleResources by tasks.registering {
    group = "verification"
    description = "Checks English/Russian resource parity and rejects untranslated Russian text in the default locale."
    val english = layout.projectDirectory.file("src/main/res/values/strings.xml")
    val russian = layout.projectDirectory.file("src/main/res/values-ru/strings.xml")
    inputs.files(english, russian)
    doLast {
        fun names(file: File) = Regex("<string\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
        val englishNames = names(english.asFile)
        val russianNames = names(russian.asFile)
        check(englishNames == russianNames) { "English/Russian string resources differ: EN-only=${englishNames - russianNames}, RU-only=${russianNames - englishNames}" }
        val defaultText = english.asFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        check(!Regex("[А-Яа-яЁё]").containsMatchIn(defaultText)) { "Default string resources contain Russian text" }
    }
}

val verifyUiResources by tasks.registering {
    group = "verification"
    description = "Rejects hardcoded Cyrillic text in user-visible Compose arguments."
    val uiSources = fileTree("src/main/java/ru/anisimov/keenwg/ui") { include("**/*.kt") }
    inputs.files(uiSources)
    doLast {
        val visibleArgument = Regex("""\bText\s*\(|contentDescription\s*=|onClickLabel\s*=|\b(?:title|label|placeholder|subtitle|detail)\s*=""")
        val cyrillic = Regex("[А-Яа-яЁё]")
        val findings = uiSources.files.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (cyrillic.containsMatchIn(line) && visibleArgument.containsMatchIn(line)) {
                    "${file.relativeTo(projectDir)}:${index + 1}"
                } else null
            }
        }
        check(findings.isEmpty()) { "Hardcoded Cyrillic UI text remains:\n${findings.joinToString("\n")}" }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyCompanionAsset, verifyFileProviderPolicy, verifyLocaleResources, verifyUiResources)
}

tasks.named("check").configure { dependsOn(verifyFileProviderPolicy, verifyLocaleResources, verifyUiResources) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.github.mwiede:jsch:2.28.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
