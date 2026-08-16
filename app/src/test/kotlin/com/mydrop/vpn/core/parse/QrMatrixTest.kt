package com.mydrop.vpn.core.parse

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.LuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These decode what was encoded rather than asserting on module counts. A grid of the right size
 * that no reader can resolve would pass any structural check and fail the only thing the feature
 * is for — another phone's camera reading it back.
 */
class QrMatrixTest {

    private val realisticLink =
        "vless://11111111-2222-3333-4444-555555555555@de.example.com:443" +
            "?security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=xR8LmN2pQvT7yZ4aB6cD9eF1gH3jK5lM7nP9qR2sT4U&sid=a1b2c3d4" +
            "&type=tcp&flow=xtls-rprx-vision#Germany"

    /**
     * Renders the grid the way a screen would, then reads it back. The scale is not incidental:
     * the detector locates a code by sampling across it, so a one-pixel-per-module image is
     * below what any real camera would ever hand it.
     */
    private fun decode(matrix: com.mydrop.vpn.core.parse.QrMatrix): String {
        val scale = 6
        val quiet = 4 * scale
        val side = matrix.size * scale + quiet * 2
        val bits = BitMatrix(side, side)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isDark(x, y)) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        bits.set(quiet + x * scale + dx, quiet + y * scale + dy)
                    }
                }
            }
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val out = row?.takeIf { it.size >= side } ?: ByteArray(side)
                for (x in 0 until side) out[x] = if (bits.get(x, y)) 0 else 255.toByte()
                return out
            }

            override fun getMatrix(): ByteArray {
                val out = ByteArray(side * side)
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        out[y * side + x] = if (bits.get(x, y)) 0 else 255.toByte()
                    }
                }
                return out
            }
        }
        return QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source))).text
    }

    @Test
    fun `a share link survives the round trip`() {
        val matrix = requireNotNull(QrMatrix.of(realisticLink))

        assertEquals(realisticLink, decode(matrix))
    }

    @Test
    fun `a subscription url survives the round trip`() {
        val url = "https://provider.example.com/sub/9f3b1c7a-4e2d?flow=xtls"
        val matrix = requireNotNull(QrMatrix.of(url))

        assertEquals(url, decode(matrix))
    }

    @Test
    fun `non latin text survives the round trip`() {
        val text = "Германия · Франкфурт"
        val matrix = requireNotNull(QrMatrix.of(text))

        assertEquals(text, decode(matrix))
    }

    @Test
    fun `the grid is square and has room for the finder patterns`() {
        val matrix = requireNotNull(QrMatrix.of(realisticLink))

        // The smallest QR version is 21x21 and every version is odd-sized.
        assertTrue(matrix.size >= 21)
        assertEquals(1, matrix.size % 2)
    }

    @Test
    fun `empty text has no code`() {
        assertNull(QrMatrix.of(""))
    }
}
