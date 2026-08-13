package ru.anisimov.keenwg.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import ru.anisimov.keenwg.data.support.SupportExport
import ru.anisimov.keenwg.R

/** Keeps one short-lived plaintext export in app cache and grants read access only to the chosen target. */
fun shareConf(context: Context, fileName: String, conf: String) {
    val dir = File(context.cacheDir, "confs").apply { mkdirs() }
    dir.listFiles()?.filter { it.isFile && it.extension.equals("conf", ignoreCase = true) }
        ?.forEach { it.delete() }
    val file = File(dir, "${sanitizeConfFileName(fileName)}.conf")
    file.writeText(conf)
    file.setReadable(false, false)
    file.setReadable(true, true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(context.getString(R.string.share_wireguard_clip_label), uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.share_wireguard_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun sanitizeConfFileName(value: String): String {
    val safe = value.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('.', '_', '-')
        .take(64)
    return safe.ifBlank { "peer" }
}

/** Writes only the already-sanitized bounded companion report, then grants temporary read access. */
fun shareSupportReport(context: Context, export: SupportExport) {
    val files = writeSupportExportFiles(context.cacheDir, export.bundle.generatedAt, export.json, export.text)
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val clip = ClipData.newRawUri(context.getString(R.string.share_support_clip_label), uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_support_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun writeSupportExportFiles(cacheDir: File, generatedAt: String, json: String, text: String): List<File> {
    require(json.toByteArray().size in 1..SUPPORT_JSON_LIMIT)
    require(text.toByteArray().size <= SUPPORT_TEXT_LIMIT)
    val stem = supportExportStem(generatedAt)
    val directory = File(cacheDir, "support").apply { mkdirs() }
    require(directory.isDirectory && directory.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator))
    directory.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("json", "txt") }?.forEach { it.delete() }
    val files = listOf(File(directory, "$stem.json") to json, File(directory, "$stem.txt") to text)
    return files.map { (file, body) ->
        require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        file.writeText(body)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file
    }
}

private fun supportExportStem(generatedAt: String): String {
    val match = SUPPORT_TIMESTAMP.matchEntire(generatedAt)
    return if (match == null) "keenwg-support" else "keenwg-support-${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}-${match.groupValues[4]}${match.groupValues[5]}${match.groupValues[6]}"
}

private const val SUPPORT_JSON_LIMIT = 64 * 1024
private const val SUPPORT_TEXT_LIMIT = 16 * 1024
private val SUPPORT_TIMESTAMP = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})Z$")
