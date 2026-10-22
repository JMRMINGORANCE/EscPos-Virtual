package com.copidev.escposvirtual.core

import java.io.ByteArrayOutputStream

/** Elemento dibujable de un ticket. Todas las medidas están en puntos de impresora (203 ppp). */
sealed class Item {
    abstract val x: Int
    abstract val y: Int
    abstract val w: Int
    abstract val h: Int
}

/**
 * Tramo de texto con un estilo uniforme. Cada carácter ocupa una celda de
 * [cellW] x [cellH] puntos (ya escalada) y se separa [spacing] puntos del siguiente.
 */
class TextItem(
    override val x: Int,
    override val y: Int,
    val text: String,
    val cellW: Int,
    val cellH: Int,
    val spacing: Int,
    val bold: Boolean,
    val underline: Int,
    val reverse: Boolean,
    val red: Boolean,
    val upsideDown: Boolean,
) : Item() {
    override val w: Int get() = text.length * (cellW + spacing)
    override val h: Int get() = cellH
}

/** Imagen monocroma: [pixels] tiene w*h valores, 1 = punto negro. */
class ImageItem(
    override val x: Int,
    override val y: Int,
    override val w: Int,
    override val h: Int,
    val pixels: ByteArray,
    val red: Boolean = false,
) : Item()

/** Etiqueta informativa (no la imprimiría una impresora real): logos NV desconocidos, avisos… */
class NoteItem(
    override val x: Int,
    override val y: Int,
    override val w: Int,
    override val h: Int,
    val text: String,
) : Item()

class Ticket(val id: Int, val paperDots: Int, val source: String) {
    val createdAt: Long = System.currentTimeMillis()
    val items = ArrayList<Item>()
    val events = ArrayList<String>()
    val log = ArrayList<String>()
    val raw = ByteArrayOutputStream()
    var height = 0
    /** 0 = sin corte, 1 = corte parcial, 2 = corte total. */
    var cut = 0
    var truncated = false

    val isEmpty: Boolean get() = items.isEmpty() && events.isEmpty()

    fun hexDump(limit: Int = 256 * 1024): String {
        val bytes = raw.toByteArray()
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * 4 + 64)
        var i = 0
        while (i < n) {
            sb.append(String.format("%06X  ", i))
            for (j in 0 until 16) {
                if (i + j < n) sb.append(String.format("%02X ", bytes[i + j].toInt() and 0xFF)) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(' ')
            for (j in 0 until 16) {
                if (i + j >= n) break
                val c = bytes[i + j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        if (bytes.size > n) sb.append("… (${bytes.size - n} bytes más)\n")
        return sb.toString()
    }

    companion object {
        const val MAX_HEIGHT = 40000
        const val MAX_LOG = 4000
    }
}
