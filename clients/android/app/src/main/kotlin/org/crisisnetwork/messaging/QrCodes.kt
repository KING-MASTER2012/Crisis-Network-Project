package org.crisisnetwork.android.messaging

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as a square of modules. `get(x, y)` is `true` for a dark module. No quiet zone is included. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * QR codes without Google Play services and without a machine-learning model: ZXing (plain Java)
 * draws them and reads them from the camera's luminance plane. A contact card is tiny, so the
 * simpler reader is enough, and the app gains no native library (which would have to be checked
 * for 16 KB page alignment).
 */
object QrCodes {
    /** Draws [text] at the lowest error correction level (the biggest modules for its size). */
    fun encode(text: String): QrMatrix {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 0,
            )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        val dark = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) dark[y * size + x] = bits[x, y]
        }
        return QrMatrix(size, dark)
    }
}

/** Reads QR codes from the luminance (Y) plane of camera frames. One instance per analysis thread. */
class QrDecoder {
    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }

    /**
     * The text of a QR code found in the frame, or `null`. [rowStride] is the distance in bytes
     * between two rows of [luma]; [width] x [height] is the visible part.
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? =
        try {
            val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}
