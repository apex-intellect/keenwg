package ru.anisimov.keenwg.ui.connections

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportQrDecoderTest {
    @Test fun `decoder returns exact utf8 payload from camera pixels`() {
        val payload = "vless://credential@vpn.example:443?security=reality"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(320 * 320) { index ->
            if (matrix[index % 320, index / 320]) 0xff000000.toInt() else 0xffffffff.toInt()
        }

        assertEquals(payload, decodeImportQr(320, 320, pixels).toString(Charsets.UTF_8))
    }
}
