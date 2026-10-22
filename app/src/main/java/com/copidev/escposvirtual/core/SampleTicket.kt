package com.copidev.escposvirtual.core

import java.io.ByteArrayOutputStream

/** Ticket de demostración que recorre los comandos más habituales. */
object SampleTicket {
    fun bytes(paperDots: Int = 576): ByteArray {
        val o = ByteArrayOutputStream()
        fun b(vararg v: Int) = v.forEach { o.write(it) }
        val cp858 = CodePage.byId(19)
        fun t(s: String) = o.write(cp858.encode(s))
        val cols = paperDots / 12

        b(0x1B, 0x40)                 // ESC @
        b(0x1B, 0x74, 19)             // ESC t 19 → PC858 (€, ñ, tildes)
        b(0x1B, 0x61, 1)              // centrado
        b(0x1D, 0x21, 0x11)           // doble ancho y alto
        b(0x1B, 0x45, 1)
        t("IMPRESORA VIRTUAL\n")
        b(0x1D, 0x21, 0x00); b(0x1B, 0x45, 0)
        t("Ticket de prueba ESC/POS\n")
        t("Cañería Ñandú · 12,50 €\n")
        b(0x1B, 0x61, 0)
        t("-".repeat(cols) + "\n")
        fun row(l: String, r: String) = t(l + " ".repeat((cols - l.length - r.length).coerceAtLeast(1)) + r + "\n")
        row("2 x Café con leche", "3,00 €")
        row("1 x Tostada de jamón", "4,50 €")
        row("1 x Zumo de naranja", "2,80 €")
        t("-".repeat(cols) + "\n")
        b(0x1B, 0x45, 1); b(0x1D, 0x21, 0x01)
        row("TOTAL", "10,30 €")
        b(0x1B, 0x45, 0); b(0x1D, 0x21, 0x00)
        t("\n")
        b(0x1B, 0x2D, 1); t("Subrayado"); b(0x1B, 0x2D, 0); t("  ")
        b(0x1D, 0x42, 1); t(" Inverso "); b(0x1D, 0x42, 0); t("  ")
        b(0x1B, 0x4D, 1); t("Fuente B\n"); b(0x1B, 0x4D, 0)
        b(0x1B, 0x61, 2); t("Alineado a la derecha\n"); b(0x1B, 0x61, 1)
        t("\n")

        // Código de barras EAN-13 con HRI debajo
        b(0x1D, 0x48, 2); b(0x1D, 0x68, 80); b(0x1D, 0x77, 2)
        b(0x1D, 0x6B, 67, 12); t("841234567890")
        t("\n")

        // QR
        val qr = "https://github.com/JMRMINGORANCE/escpos-virtual".toByteArray()
        b(0x1D, 0x28, 0x6B, 3, 0, 49, 67, 6)
        b(0x1D, 0x28, 0x6B, 3, 0, 49, 69, 49)
        b(0x1D, 0x28, 0x6B, (qr.size + 3) and 0xFF, (qr.size + 3) shr 8, 49, 80, 48); o.write(qr)
        b(0x1D, 0x28, 0x6B, 3, 0, 49, 81, 48)
        t("\n")

        // Imagen raster GS v 0: degradado de rombos de 128×48
        val w = 128; val h = 48; val rb = w / 8
        b(0x1D, 0x76, 0x30, 0, rb, 0, h, 0)
        for (yy in 0 until h) for (xb in 0 until rb) {
            var v = 0
            for (bit in 0 until 8) {
                val xx = xb * 8 + bit
                val d = Math.abs(xx % 32 - 16) + Math.abs(yy % 24 - 12)
                if (d < 12) v = v or (0x80 shr bit)
            }
            o.write(v)
        }
        t("\nGracias por su visita\n")
        b(0x1B, 0x64, 3)              // ESC d 3
        b(0x1D, 0x56, 66, 0)          // GS V B 0 → corte parcial
        return o.toByteArray()
    }
}
