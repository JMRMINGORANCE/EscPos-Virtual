package com.copidev.escposvirtual.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.google.zxing.pdf417.encoder.PDF417
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Matriz de módulos monocroma (1 = negro). */
class Matrix(val w: Int, val h: Int, val bits: ByteArray) {
    fun scaled(sx: Int, sy: Int): Matrix {
        val nw = w * sx
        val nh = h * sy
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val src = (y / sy) * w
            val dst = y * nw
            for (x in 0 until nw) out[dst + x] = bits[src + x / sx]
        }
        return Matrix(nw, nh, out)
    }

    companion object {
        fun from(m: BitMatrix): Matrix {
            val out = ByteArray(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) out[y * m.width + x] = 1
            return Matrix(m.width, m.height, out)
        }
    }
}

object Codes {
    private fun latin1(data: ByteArray) = String(data, Charsets.ISO_8859_1)

    /** Códigos de barras 1D de GS k. Devuelve la fila de módulos (1 = barra) y el texto legible (HRI). */
    fun barcode(type: Int, data: ByteArray): Pair<ByteArray, String> {
        val (format, text) = when (type) {
            0, 65 -> BarcodeFormat.UPC_A to latin1(data)
            1, 66 -> BarcodeFormat.UPC_E to latin1(data)
            2, 67 -> BarcodeFormat.EAN_13 to latin1(data)
            3, 68 -> BarcodeFormat.EAN_8 to latin1(data)
            4, 69 -> BarcodeFormat.CODE_39 to latin1(data).trim('*')
            5, 70 -> BarcodeFormat.ITF to latin1(data)
            6, 71 -> BarcodeFormat.CODABAR to latin1(data)
            72 -> BarcodeFormat.CODE_93 to latin1(data)
            73 -> BarcodeFormat.CODE_128 to code128Text(data)
            else -> throw IllegalArgumentException("tipo de código de barras $type no soportado")
        }
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val m = MultiFormatWriter().encode(text, format, 0, 1, hints)
        val row = ByteArray(m.width)
        for (x in 0 until m.width) if (m.get(x, 0)) row[x] = 1
        // La impresora real muestra el dígito de control aunque no se envíe.
        val hri = when {
            format == BarcodeFormat.EAN_13 && text.length == 12 -> text + checkDigit(text)
            format == BarcodeFormat.EAN_8 && text.length == 7 -> text + checkDigit(text)
            format == BarcodeFormat.UPC_A && text.length == 11 -> text + checkDigit(text)
            else -> text
        }
        return row to hri
    }

    private fun checkDigit(digits: String): Int {
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) sum += (ch - '0') * if (i % 2 == 0) 3 else 1
        return (10 - sum % 10) % 10
    }

    /** Traduce los prefijos de juego de caracteres de Epson ({A, {B, {C, {1…) a texto plano. */
    private fun code128Text(data: ByteArray): String {
        val sb = StringBuilder()
        var set = 'B'
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            if (b == '{'.code && i + 1 < data.size) {
                when (val n = (data[i + 1].toInt() and 0xFF).toChar()) {
                    'A', 'B', 'C' -> set = n
                    '{' -> sb.append('{')
                    else -> {} // FNC1-4 y SHIFT: se ignoran
                }
                i += 2
                continue
            }
            if (set == 'C') sb.append(String.format("%02d", b % 100)) else sb.append(b.toChar())
            i++
        }
        return sb.toString()
    }

    fun qr(data: ByteArray, ecLevel: Int): Matrix {
        val ec = when (ecLevel) {
            49 -> ErrorCorrectionLevel.M
            50 -> ErrorCorrectionLevel.Q
            51 -> ErrorCorrectionLevel.H
            else -> ErrorCorrectionLevel.L
        }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ec,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 0,
        )
        return Matrix.from(MultiFormatWriter().encode(latin1(data), BarcodeFormat.QR_CODE, 0, 0, hints))
    }

    fun pdf417(data: ByteArray, ecLevel: Int, rowHeight: Int): Matrix {
        val enc = PDF417()
        enc.generateBarcodeLogic(latin1(data), ecLevel.coerceIn(0, 8), false)
        val rows = enc.barcodeMatrix.getScaledMatrix(1, rowHeight.coerceAtLeast(1))
        val h = rows.size
        val w = if (h > 0) rows[0].size else 0
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (rows[y][x].toInt() == 1) out[y * w + x] = 1
        return Matrix(w, h, out)
    }

    fun dataMatrix(data: ByteArray): Matrix {
        val hints = mapOf(EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE, EncodeHintType.MARGIN to 0)
        return Matrix.from(MultiFormatWriter().encode(latin1(data), BarcodeFormat.DATA_MATRIX, 0, 0, hints))
    }

    fun aztec(data: ByteArray): Matrix {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        return Matrix.from(MultiFormatWriter().encode(latin1(data), BarcodeFormat.AZTEC, 0, 0, hints))
    }
}
