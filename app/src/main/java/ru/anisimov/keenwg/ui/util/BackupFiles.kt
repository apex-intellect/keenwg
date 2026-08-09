package ru.anisimov.keenwg.ui.util

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

fun readBackupArchive(resolver: ContentResolver, uri: Uri): ByteArray {
    val input = requireNotNull(resolver.openInputStream(uri))
    input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= BACKUP_FILE_LIMIT)
            output.write(buffer, 0, count)
        }
        return output.toByteArray().also { bytes -> require(bytes.isNotEmpty()) }
    }
}

fun shareBackupArchive(context: Context, archive: ByteArray) {
    val file = writeBackupExportFile(context.cacheDir, archive)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = BACKUP_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("KeenWG encrypted backup", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(ru.anisimov.keenwg.R.string.backup_share_chooser)))
}

internal fun writeBackupExportFile(cacheDir: File, archive: ByteArray): File {
    require(archive.size in 1..BACKUP_FILE_LIMIT)
    val directory = File(cacheDir, "backup").apply { mkdirs() }
    require(directory.isDirectory && directory.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator))
    directory.listFiles()?.filter(File::isFile)?.forEach(File::delete)
    val file = File(directory, "keenwg-backup.kwgb")
    require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
    file.writeBytes(archive)
    file.setReadable(false, false)
    file.setReadable(true, true)
    return file
}

const val BACKUP_MIME = "application/vnd.keenwg.backup"
private const val BACKUP_FILE_LIMIT = 4 * 1024 * 1024
