package com.mydrop.vpn.core.parse

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code as its raw module grid, one boolean per module, row-major.
 *
 * Deliberately not a bitmap: the caller draws it, so the code stays sharp at any size and can
 * take the screen's own colours instead of carrying baked-in black and white.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {

    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {

        /**
         * Encodes [text], or returns null when it cannot be represented — a share link long
         * enough to overflow the largest QR version is the realistic case.
         *
         * Error correction stays at M. A phone screen is a clean, well-lit target, so the extra
         * redundancy of Q or H would only buy density: the same link would need a larger grid,
         * with finer modules for the other camera to resolve.
         */
        fun of(text: String): QrMatrix? {
            if (text.isEmpty()) return null
            return runCatching {
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
                    ?: return null
                val size = matrix.width
                val dark = BooleanArray(size * size)
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        dark[y * size + x] = matrix.get(x, y).toInt() == 1
                    }
                }
                QrMatrix(size, dark)
            }.getOrNull()
        }
    }
}
