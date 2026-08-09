package ru.anisimov.keenwg.ui.connections

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

fun decodeImportQr(width: Int, height: Int, pixels: IntArray): ByteArray {
    require(width > 0 && height > 0 && pixels.size == width * height)
    val source = RGBLuminanceSource(width, height, pixels)
    val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
    return result.text.toByteArray(Charsets.UTF_8)
}
