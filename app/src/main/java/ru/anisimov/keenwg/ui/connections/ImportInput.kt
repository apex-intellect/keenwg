package ru.anisimov.keenwg.ui.connections

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun readImportBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    val limit = 1_048_577
    while (output.size() < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
