package com.copidev.escposvirtual.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Conexión que envía datos a la impresora (un socket TCP, un archivo importado…). */
interface Host {
    val name: String
    /** Respuestas de estado (DLE EOT, GS r, GS I, ASB…). */
    fun reply(bytes: ByteArray)
    var asbEnabled: Boolean
}

interface PrinterListener {
    fun onTicket(ticket: Ticket)
    fun onEvent(message: String)
}

/** Estado simulado de la impresora; se lee al responder a los comandos de estado. */
class PrinterStatus {
    @Volatile var paperNearEnd = false
    @Volatile var paperOut = false
    @Volatile var coverOpen = false
    @Volatile var drawerOpen = false
    @Volatile var offline = false

    val isOffline: Boolean get() = offline || paperOut || coverOpen
}

class PrinterConfig(
    /** Ancho imprimible en puntos: 576 (80 mm) o 384 (58 mm). */
    val paperDots: Int = 576,
    val defaultCodePage: Int = 0,
    val forceUtf8: Boolean = false,
)

private object NeedMore : RuntimeException() {
    private fun readResolve(): Any = NeedMore
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Intérprete ESC/POS incremental. Recibe trozos de bytes, procesa los comandos completos y
 * construye [Ticket]s. Un ticket termina en cada corte de papel o al cerrarse la conexión.
 */
class EscPosPrinter(
    @Volatile var config: PrinterConfig,
    val status: PrinterStatus,
    private val listener: PrinterListener,
) {
    // ---------- cursor de lectura ----------
    private class Cur(val buf: ByteArray, val len: Int, var p: Int) {
        fun u8(): Int {
            if (p >= len) throw NeedMore
            return buf[p++].toInt() and 0xFF
        }
        fun peek(off: Int = 0): Int {
            if (p + off >= len) throw NeedMore
            return buf[p + off].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > MAX_COMMAND) throw IllegalStateException("longitud no válida: $n")
            if (len - p < n) throw NeedMore
            val out = buf.copyOfRange(p, p + n)
            p += n
            return out
        }
        fun skip(n: Int) {
            if (n < 0 || n > MAX_COMMAND) throw IllegalStateException("longitud no válida: $n")
            if (len - p < n) throw NeedMore
            p += n
        }
        val remaining get() = len - p
    }

    // ---------- estado de impresión ----------
    private var ticketSeq = 0
    private var ticket = newTicket("—")

    private var y = 0
    private var lineX = 0
    private val pending = ArrayList<Pending>()

    private var font = 0
    private var scaleX = 1
    private var scaleY = 1
    private var bold = false
    private var underline = 0
    private var reverse = false
    private var red = false
    private var upsideDown = false
    private var charSpacing = 0
    private var align = 0
    private var lineSpacing = DEFAULT_LINE_SPACING
    private var leftMargin = 0
    private var areaWidth = Int.MAX_VALUE
    private var codePage: CodePage = CodePage.byId(0)
    private var intl = 0
    private var kanji = false
    private var kanjiCharset: Charset? = null
    private var tabs = defaultTabs()

    private var hriPos = 0
    private var hriFont = 0
    private var barcodeHeight = 162
    private var barcodeModule = 3

    private var qrModule = 3
    private var qrEc = 48
    private var qrData = ByteArray(0)
    private var pdfModule = 3
    private var pdfRowHeight = 3
    private var pdfEc = 1
    private var pdfData = ByteArray(0)
    private var twoDModule = 3
    private var dmData = ByteArray(0)
    private var aztecData = ByteArray(0)

    private var gfxBuffer = ArrayList<Pair<Matrix, Boolean>>() // (imagen, rojo)
    private var gfxScaleX = 1
    private var gfxScaleY = 1
    private val nvGraphics = HashMap<Int, Matrix>()
    private val nvImages = HashMap<Int, Matrix>()
    private var downloadImage: Matrix? = null

    private var pendingFinish = -1
    private var lastHost: Host? = null
    private val asbHosts = LinkedHashSet<Host>()

    private var desc = ""
    private var textRun: String? = null
    private var lastTextTicket: Ticket? = null
    private var lastTextLog = -1
    private var lastTextStart = 0
    private var lastTextEnd = -1
    private var lastTextValue = ""

    private fun ellipsize(s: String) = if (s.length > 80) s.substring(0, 80) + "…" else s

    init {
        reset()
    }

    private fun newTicket(source: String) = Ticket(++ticketSeq, config.paperDots, source)

    private fun defaultTabs(): IntArray = IntArray(32) { (it + 1) * 8 * 12 }

    private fun reset() {
        pending.clear()
        lineX = 0
        font = 0; scaleX = 1; scaleY = 1
        bold = false; underline = 0; reverse = false; red = false; upsideDown = false
        charSpacing = 0; align = 0; lineSpacing = DEFAULT_LINE_SPACING
        leftMargin = 0; areaWidth = Int.MAX_VALUE
        codePage = CodePage.byId(config.defaultCodePage)
        intl = 0
        kanji = false
        tabs = defaultTabs()
        hriPos = 0; hriFont = 0; barcodeHeight = 162; barcodeModule = 3
        qrModule = 3; qrEc = 48
        pdfModule = 3; pdfRowHeight = 3; pdfEc = 1
        twoDModule = 3
    }

    // =====================================================================
    // API pública
    // =====================================================================

    /** Procesa los comandos completos de buf[0 until len] y devuelve cuántos bytes ha consumido. */
    @Synchronized
    fun process(buf: ByteArray, len: Int, host: Host): Int {
        if (lastHost !== host) {
            lastHost = host
        }
        if (ticket.raw.size() == 0 && ticket.isEmpty) ticket = Ticket(ticket.id, config.paperDots, host.name)
        var pos = 0
        while (pos < len) {
            val c = Cur(buf, len, pos)
            desc = ""
            textRun = null
            try {
                handle(c, host)
            } catch (e: NeedMore) {
                break
            } catch (e: Exception) {
                // Comando corrupto o no soportado: lo saltamos byte a byte para no bloquearnos.
                c.p = pos + 1
                desc = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            val t = ticket
            val offset = t.raw.size()
            t.raw.write(buf, pos, c.p - pos)
            if (textRun != null && lastTextTicket === t && lastTextLog == t.log.size - 1 && lastTextEnd == offset) {
                // Texto que llega en varios trozos TCP: una sola entrada en el registro.
                val merged = lastTextValue + textRun
                lastTextValue = merged
                t.log[lastTextLog] = String.format("%06X  %-26s %s", lastTextStart, "", "Texto «" + ellipsize(merged) + "»")
                lastTextEnd = t.raw.size()
            } else if (desc.isNotEmpty() && t.log.size < Ticket.MAX_LOG) {
                t.log.add(String.format("%06X  %-26s %s", offset, hexShort(buf, pos, c.p), desc))
                if (textRun != null) {
                    lastTextTicket = t; lastTextLog = t.log.size - 1; lastTextStart = offset
                    lastTextEnd = t.raw.size(); lastTextValue = textRun!!
                } else lastTextTicket = null
            }
            textRun = null
            pos = c.p
            if (pendingFinish >= 0) {
                val cut = pendingFinish
                pendingFinish = -1
                finishTicket(cut, host.name)
            }
        }
        return pos
    }

    /** La conexión se ha cerrado o lleva un rato sin enviar datos: se cierra el ticket en curso. */
    @Synchronized
    fun endOfJob(host: Host) {
        asbHosts.remove(host)
        if (pending.isNotEmpty()) printLine()
        finishTicket(0, host.name)
    }

    @Synchronized
    fun idle(host: Host) {
        if (pending.isNotEmpty() || !ticket.isEmpty) {
            if (pending.isNotEmpty()) printLine()
            finishTicket(0, host.name)
        }
    }

    @Synchronized
    fun detach(host: Host) {
        asbHosts.remove(host)
    }

    /** Envía un ASB a las conexiones que lo tengan activado (llamar tras cambiar [status]). */
    @Synchronized
    fun statusChanged() {
        val bytes = asbBytes()
        for (h in asbHosts.toList()) runCatching { h.reply(bytes) }
    }

    @Synchronized
    fun configChanged(newConfig: PrinterConfig) {
        config = newConfig
        codePage = CodePage.byId(newConfig.defaultCodePage)
        if (ticket.isEmpty && ticket.raw.size() == 0) ticket = Ticket(ticket.id, newConfig.paperDots, ticket.source)
    }

    // =====================================================================
    // Despacho de comandos
    // =====================================================================

    private fun handle(c: Cur, host: Host) {
        val b = c.peek()
        when {
            b >= 0x20 && b != 0x7F -> text(c)
            b == 0x0A -> { c.u8(); printLine(); desc = "LF · imprimir y avanzar línea" }
            b == 0x0D -> { c.u8(); desc = "CR" }
            b == 0x09 -> { c.u8(); tab(); desc = "HT · tabulación" }
            b == 0x0C -> { c.u8(); if (pending.isNotEmpty()) printLine(); desc = "FF · fin de página" }
            b == 0x18 -> { c.u8(); desc = "CAN · cancelar (modo página)" }
            b == 0x10 -> dle(c, host)
            b == 0x1B -> esc(c, host)
            b == 0x1C -> fs(c)
            b == 0x1D -> gs(c, host)
            b == 0x07 -> { c.u8(); event("Pitido (BEL)"); desc = "BEL · pitido" }
            else -> { c.u8(); desc = String.format("Byte de control 0x%02X ignorado", b) }
        }
    }

    // ---------- texto ----------
    private fun text(c: Cur) {
        val sb = StringBuilder()
        val utf8 = config.forceUtf8
        while (c.p < c.len) {
            val b = c.buf[c.p].toInt() and 0xFF
            if (b < 0x20 || b == 0x7F) break
            if (utf8 && b >= 0x80) {
                val n = when {
                    b >= 0xF0 -> 4
                    b >= 0xE0 -> 3
                    b >= 0xC0 -> 2
                    else -> 1
                }
                if (c.remaining < n) { if (sb.isEmpty()) throw NeedMore else break }
                val s = String(c.buf, c.p, n, Charsets.UTF_8)
                c.p += n
                for (ch in s) { putChar(ch, 1); sb.append(ch) }
                continue
            }
            if (kanji && b >= 0x81) {
                if (c.remaining < 2) { if (sb.isEmpty()) throw NeedMore else break }
                val s = decodeKanji(c.buf, c.p)
                c.p += 2
                putChar(s, 2); sb.append(s)
                continue
            }
            c.p++
            val ch = decodeByte(b)
            putChar(ch, 1)
            sb.append(ch)
        }
        textRun = sb.toString()
        desc = "Texto «" + ellipsize(sb.toString()) + "»"
    }

    private fun decodeByte(b: Int): Char {
        if (b < 0x80) {
            val m = INTL[intl]
            if (m != null) m[b.toChar()]?.let { return it }
            return b.toChar()
        }
        return codePage.decode(b)
    }

    private fun decodeKanji(buf: ByteArray, p: Int): Char {
        val cs = kanjiCharset ?: Charset.forName("GB18030")
        return try {
            val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            dec.decode(ByteBuffer.wrap(buf, p, 2)).toString().firstOrNull() ?: '?'
        } catch (e: Exception) {
            '?'
        }
    }

    // ---------- DLE ----------
    private fun dle(c: Cur, host: Host) {
        c.u8()
        when (val n = c.u8()) {
            0x04 -> {
                val k = c.u8()
                if (k == 7 || k == 8) c.u8()
                val st = status
                val r = when (k) {
                    1 -> 0x16 or (if (st.drawerOpen) 0x04 else 0) or (if (st.isOffline) 0x08 else 0)
                    2 -> 0x12 or (if (st.coverOpen) 0x04 else 0) or (if (st.paperOut) 0x20 else 0)
                    3 -> 0x12
                    4 -> 0x12 or (if (st.paperNearEnd) 0x0C else 0) or (if (st.paperOut) 0x60 else 0)
                    else -> -1
                }
                if (r >= 0) host.reply(byteArrayOf(r.toByte()))
                desc = "DLE EOT $k · estado en tiempo real" + if (r >= 0) String.format(" → respondido 0x%02X", r) else ""
            }
            0x05 -> { c.u8(); desc = "DLE ENQ · petición en tiempo real" }
            0x14 -> {
                val fn = c.u8()
                when (fn) {
                    1 -> { val m = c.u8(); val t = c.u8(); drawer(m, t * 100) }
                    2, 3 -> c.skip(2)
                    7 -> c.skip(1)
                    8 -> c.skip(7)
                    else -> {}
                }
                if (fn != 1) desc = "DLE DC4 $fn"
            }
            else -> desc = String.format("DLE 0x%02X desconocido", n)
        }
    }

    // ---------- ESC ----------
    private fun esc(c: Cur, host: Host) {
        c.u8()
        val cmd = c.u8()
        when (cmd.toChar()) {
            '@' -> { reset(); desc = "ESC @ · inicializar impresora" }
            '!' -> {
                val n = c.u8()
                font = n and 1
                bold = n and 0x08 != 0
                scaleY = if (n and 0x10 != 0) 2 else 1
                scaleX = if (n and 0x20 != 0) 2 else 1
                underline = if (n and 0x80 != 0) 1 else 0
                desc = "ESC ! · modo de impresión" + modeDesc()
            }
            ' ' -> { charSpacing = c.u8(); desc = "ESC SP · espacio entre caracteres = $charSpacing" }
            '$' -> { lineX = c.u16().coerceAtMost(area()); desc = "ESC $ · posición absoluta $lineX" }
            '\\' -> { val v = c.u16().toShort().toInt(); lineX = (lineX + v).coerceIn(0, area()); desc = "ESC \\ · posición relativa $v" }
            '%' -> { c.u8(); desc = "ESC % · juego de caracteres definidos por el usuario" }
            '&' -> {
                val yb = c.u8(); val c1 = c.u8(); val c2 = c.u8()
                for (i in c1..c2) { val x = c.u8(); c.skip(yb * x) }
                desc = "ESC & · definir caracteres de usuario (ignorado)"
            }
            '*' -> escImage(c)
            '-' -> { underline = c.u8().let { if (it == 48) 0 else if (it == 49) 1 else if (it == 50) 2 else it.coerceIn(0, 2) }; desc = "ESC - · subrayado $underline" }
            '2' -> { lineSpacing = DEFAULT_LINE_SPACING; desc = "ESC 2 · interlineado por defecto" }
            '3' -> { lineSpacing = c.u8(); desc = "ESC 3 · interlineado = $lineSpacing" }
            '=' -> { c.u8(); desc = "ESC = · seleccionar periférico" }
            '?' -> { c.u8(); desc = "ESC ? · borrar carácter de usuario" }
            'B' -> { val n = c.u8(); c.u8(); event("Pitido ×$n"); desc = "ESC B · pitido" }
            'D' -> {
                val list = ArrayList<Int>()
                while (true) { val v = c.u8(); if (v == 0) break; if (list.size < 32) list.add(v) }
                val adv = advance()
                tabs = list.map { it * adv }.toIntArray()
                desc = "ESC D · tabulaciones ${list.joinToString(",")}"
            }
            'E' -> { bold = c.u8() and 1 == 1; desc = "ESC E · negrita ${onOff(bold)}" }
            'G' -> { bold = c.u8() and 1 == 1; desc = "ESC G · doble impacto ${onOff(bold)}" }
            'J' -> { val n = c.u8(); printLine(n); desc = "ESC J · imprimir y avanzar $n puntos" }
            'K' -> { c.u8(); desc = "ESC K · retroceder papel (ignorado)" }
            'L' -> desc = "ESC L · modo página (se trata como modo estándar)"
            'S' -> desc = "ESC S · modo estándar"
            'M' -> { font = c.u8().let { if (it >= 48) it - 48 else it }.coerceIn(0, 1); desc = "ESC M · fuente ${if (font == 0) "A" else "B"}" }
            'R' -> { intl = c.u8().coerceIn(0, INTL.size - 1); desc = "ESC R · juego internacional $intl" }
            'T' -> { c.u8(); desc = "ESC T · dirección (modo página)" }
            'U' -> { c.u8(); desc = "ESC U · unidireccional" }
            'V' -> { c.u8(); desc = "ESC V · rotación 90° (no emulada)" }
            'W' -> { c.skip(8); desc = "ESC W · área de página" }
            'a' -> { align = c.u8().let { if (it >= 48) it - 48 else it }.coerceIn(0, 2); desc = "ESC a · alineación " + arrayOf("izquierda", "centro", "derecha")[align] }
            'c' -> { c.u8(); c.u8(); desc = "ESC c · configuración de panel/sensores" }
            'd' -> {
                val n = c.u8()
                if (pending.isNotEmpty()) { printLine(); y += (n - 1).coerceAtLeast(0) * lineSpacing } else y += n * lineSpacing
                desc = "ESC d · imprimir y avanzar $n líneas"
            }
            'e' -> { c.u8(); desc = "ESC e · retroceder líneas (ignorado)" }
            'i' -> { if (pending.isNotEmpty()) printLine(); pendingFinish = 1; desc = "ESC i · corte parcial" }
            'm' -> { if (pending.isNotEmpty()) printLine(); pendingFinish = 1; desc = "ESC m · corte parcial" }
            'p' -> { val m = c.u8(); val t1 = c.u8(); c.u8(); drawer(m, t1 * 2) }
            'r' -> { red = c.u8() and 1 == 1; desc = "ESC r · color " + if (red) "rojo" else "negro" }
            't' -> { val n = c.u8(); codePage = CodePage.byId(n); desc = "ESC t $n · página de códigos ${codePage.name}" + if (CodePage.ALL.none { it.id == n }) " (no disponible, se usa PC437)" else "" }
            'u' -> { c.u8(); host.reply(byteArrayOf(if (status.drawerOpen) 1 else 0)); desc = "ESC u · estado del cajón → respondido" }
            'v' -> { host.reply(byteArrayOf(paperSensor().toByte())); desc = "ESC v · estado del papel → respondido" }
            '{' -> { upsideDown = c.u8() and 1 == 1; desc = "ESC { · boca abajo ${onOff(upsideDown)}" }
            '<' -> desc = "ESC < · volver al inicio"
            '(' -> {
                val fn = c.u8(); val len = c.u16(); c.skip(len)
                if (fn == 'A'.code) event("Pitido")
                desc = "ESC ( ${fn.toChar()} · función ampliada"
            }
            '\u000C' -> { if (pending.isNotEmpty()) printLine(); desc = "ESC FF · imprimir página" }
            else -> desc = String.format("ESC 0x%02X desconocido", cmd)
        }
    }

    // ---------- FS ----------
    private fun fs(c: Cur) {
        c.u8()
        val cmd = c.u8()
        when (cmd.toChar()) {
            '&' -> { kanji = true; desc = "FS & · modo kanji/chino activado" }
            '.' -> { kanji = false; desc = "FS . · modo kanji/chino desactivado" }
            '!' -> { c.u8(); desc = "FS ! · modo de caracteres kanji" }
            '-' -> { c.u8(); desc = "FS - · subrayado kanji" }
            'C' -> {
                val n = c.u8()
                kanjiCharset = runCatching { Charset.forName(if (n == 0 || n == 48) "EUC-JP" else "Shift_JIS") }.getOrNull()
                desc = "FS C · sistema de codificación kanji $n"
            }
            'S' -> { c.skip(2); desc = "FS S · espaciado kanji" }
            'W' -> { c.u8(); desc = "FS W · cuádruple tamaño kanji" }
            '2' -> { c.skip(2 + 72); desc = "FS 2 · definir carácter kanji" }
            'p' -> {
                val n = c.u8(); val m = c.u8()
                val img = nvImages[n]
                if (img != null) block(img.scaled(if (m and 1 != 0) 2 else 1, if (m and 2 != 0) 2 else 1), false)
                else note("[Logo NV nº $n — no definido en esta impresora]")
                desc = "FS p · imprimir imagen NV $n"
            }
            'q' -> {
                val n = c.u8()
                nvImages.clear()
                for (i in 1..n) {
                    val xb = c.u16(); val yb = c.u16()
                    nvImages[i] = columnImage(c.bytes(xb * yb * 8), xb * 8, yb * 8)
                }
                desc = "FS q · definir $n imágenes NV"
            }
            '(' -> { val fn = c.u8(); val len = c.u16(); c.skip(len); desc = "FS ( ${fn.toChar()} · función ampliada" }
            else -> desc = String.format("FS 0x%02X desconocido", cmd)
        }
    }

    // ---------- GS ----------
    private fun gs(c: Cur, host: Host) {
        c.u8()
        val cmd = c.u8()
        when (cmd.toChar()) {
            '!' -> {
                val n = c.u8()
                scaleX = ((n shr 4) and 7) + 1
                scaleY = (n and 7) + 1
                desc = "GS ! · tamaño ${scaleX}×$scaleY"
            }
            '$' -> { c.skip(2); desc = "GS $ · posición vertical (modo página)" }
            '\\' -> { c.skip(2); desc = "GS \\ · posición vertical relativa" }
            '(' -> gsParen(c, host)
            '8' -> {
                val sub = c.u8()
                val len = (c.u16() or (c.u16() shl 16))
                if (sub == 'L'.code) graphics(c, len) else { c.skip(len); desc = "GS 8 ${sub.toChar()}" }
            }
            '*' -> {
                val xb = c.u8(); val yb = c.u8()
                downloadImage = columnImage(c.bytes(xb * yb * 8), xb * 8, yb * 8)
                desc = "GS * · definir imagen descargada ${xb * 8}×${yb * 8}"
            }
            '/' -> {
                val m = c.u8().let { if (it >= 48) it - 48 else it }
                val img = downloadImage
                if (img != null) block(img.scaled(if (m and 1 != 0) 2 else 1, if (m and 2 != 0) 2 else 1), false)
                desc = "GS / · imprimir imagen descargada"
            }
            'B' -> { reverse = c.u8() and 1 == 1; desc = "GS B · inverso ${onOff(reverse)}" }
            'H' -> { hriPos = c.u8().let { if (it >= 48) it - 48 else it }.coerceIn(0, 3); desc = "GS H · posición HRI $hriPos" }
            'I' -> gsInfo(c, host)
            'L' -> { leftMargin = c.u16(); desc = "GS L · margen izquierdo $leftMargin" }
            'P' -> { c.skip(2); desc = "GS P · unidades de movimiento" }
            'T' -> { c.u8(); desc = "GS T · inicio de línea" }
            'V' -> {
                val m = c.u8()
                var feed = 0
                if (m == 65 || m == 66 || m == 97 || m == 98 || m == 103 || m == 104) feed = c.u8()
                if (pending.isNotEmpty()) printLine()
                y += feed
                val full = m == 0 || m == 48 || m == 65 || m == 97 || m == 103
                pendingFinish = if (full) 2 else 1
                desc = "GS V · corte " + if (full) "total" else "parcial"
            }
            'W' -> { areaWidth = c.u16().let { if (it == 0) Int.MAX_VALUE else it }; desc = "GS W · ancho de impresión $areaWidth" }
            'a' -> {
                val n = c.u8()
                host.asbEnabled = n != 0
                if (n != 0) { asbHosts.add(host); host.reply(asbBytes()) } else asbHosts.remove(host)
                desc = "GS a · estado automático (ASB) " + if (n != 0) "activado → enviado" else "desactivado"
            }
            'b' -> { c.u8(); desc = "GS b · suavizado" }
            'f' -> { hriFont = c.u8().let { if (it >= 48) it - 48 else it } and 1; desc = "GS f · fuente HRI" }
            'h' -> { barcodeHeight = c.u8().coerceAtLeast(1); desc = "GS h · alto de código de barras $barcodeHeight" }
            'k' -> barcode(c)
            'r' -> {
                val n = c.u8()
                val r = when (n) {
                    1, 49 -> paperSensor()
                    2, 50 -> if (status.drawerOpen) 1 else 0
                    else -> 0
                }
                host.reply(byteArrayOf(r.toByte()))
                desc = String.format("GS r %d · estado → respondido 0x%02X", n, r)
            }
            'v' -> rasterImage(c)
            'w' -> { barcodeModule = c.u8().let { if (it in 68..76) it - 66 else it }.coerceIn(1, 6); desc = "GS w · ancho de módulo $barcodeModule" }
            'j', 'E', 'g' -> { c.u8(); desc = "GS ${cmd.toChar()}" }
            'c' -> desc = "GS c · contador"
            else -> desc = String.format("GS 0x%02X desconocido", cmd)
        }
    }

    private fun gsInfo(c: Cur, host: Host) {
        val n = c.u8()
        val reply: ByteArray = when (n) {
            1, 49 -> byteArrayOf(0x20)
            2, 50 -> byteArrayOf(0x02)
            3, 51 -> byteArrayOf(0x40)
            65 -> info("1.00 Virtual")
            66 -> info("EPSON")
            67 -> info(if (config.paperDots >= 576) "TM-T20 Virtual" else "TM-P20 Virtual")
            68 -> info("VIRTUAL0001")
            69 -> info("ANK")
            else -> ByteArray(0)
        }
        if (reply.isNotEmpty()) host.reply(reply)
        desc = "GS I $n · información de la impresora" + if (reply.isNotEmpty()) " → respondido" else ""
    }

    private fun info(s: String): ByteArray = byteArrayOf(0x5F) + s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun gsParen(c: Cur, host: Host) {
        val fn = c.u8()
        val len = c.u16()
        when (fn.toChar()) {
            'k' -> twoD(c, len)
            'L' -> graphics(c, len)
            'A' -> { c.skip(len); event("Página de prueba solicitada"); desc = "GS ( A · autoprueba" }
            'H' -> {
                val data = c.bytes(len)
                if (len >= 6 && data[0].toInt() == 48 && data[1].toInt() == 48) {
                    host.reply(byteArrayOf(0x37, 0x22) + data.copyOfRange(2, 6) + byteArrayOf(0))
                    desc = "GS ( H · ID de proceso → respondido"
                } else desc = "GS ( H"
            }
            else -> { c.skip(len); desc = "GS ( ${fn.toChar()} · función ampliada" }
        }
    }

    // ---------- códigos 2D ----------
    private fun twoD(c: Cur, len: Int) {
        if (len < 2) { c.skip(len); return }
        val cn = c.u8(); val f = c.u8()
        val data = c.bytes(len - 2)
        fun arg(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else 0
        when (cn) {
            49 -> when (f) { // QR
                65 -> desc = "QR · modelo ${arg(0) - 48}"
                67 -> { qrModule = arg(0).coerceIn(1, 16); desc = "QR · tamaño de módulo $qrModule" }
                69 -> { qrEc = arg(0); desc = "QR · corrección de errores " + "LMQH".getOrElse(qrEc - 48) { '?' } }
                80 -> { qrData = data.copyOfRange(1.coerceAtMost(data.size), data.size); desc = "QR · guardar datos (${qrData.size} bytes)" }
                81 -> {
                    desc = "QR · imprimir"
                    try {
                        block(Codes.qr(qrData, qrEc).scaled(qrModule, qrModule), false)
                    } catch (e: Exception) {
                        note("[QR no válido: ${e.message}]"); desc += " · ERROR: ${e.message}"
                    }
                }
                else -> desc = "QR · función $f"
            }
            48 -> when (f) { // PDF417
                67 -> { pdfModule = arg(0).coerceIn(1, 8); desc = "PDF417 · ancho de módulo" }
                68 -> { pdfRowHeight = arg(0).coerceIn(1, 8); desc = "PDF417 · alto de fila" }
                69 -> { pdfEc = if (arg(0) == 48) (arg(1) - 48).coerceIn(0, 8) else 1; desc = "PDF417 · corrección de errores" }
                80 -> { pdfData = data.copyOfRange(1.coerceAtMost(data.size), data.size); desc = "PDF417 · guardar datos (${pdfData.size} bytes)" }
                81 -> {
                    desc = "PDF417 · imprimir"
                    try {
                        block(Codes.pdf417(pdfData, pdfEc, pdfRowHeight).scaled(pdfModule, pdfModule), false)
                    } catch (e: Exception) {
                        note("[PDF417 no válido: ${e.message}]"); desc += " · ERROR"
                    }
                }
                else -> desc = "PDF417 · función $f"
            }
            53, 54 -> when (f) { // Aztec / DataMatrix
                66, 67 -> { twoDModule = arg(0).coerceIn(1, 16); desc = "2D · tamaño de módulo" }
                80 -> {
                    val d = data.copyOfRange(1.coerceAtMost(data.size), data.size)
                    if (cn == 54) dmData = d else aztecData = d
                    desc = (if (cn == 54) "DataMatrix" else "Aztec") + " · guardar datos"
                }
                81 -> {
                    desc = (if (cn == 54) "DataMatrix" else "Aztec") + " · imprimir"
                    try {
                        val m = if (cn == 54) Codes.dataMatrix(dmData) else Codes.aztec(aztecData)
                        block(m.scaled(twoDModule, twoDModule), false)
                    } catch (e: Exception) {
                        note("[Código 2D no válido: ${e.message}]")
                    }
                }
                else -> desc = "2D · función $f"
            }
            else -> desc = "GS ( k · símbolo $cn función $f (no soportado)"
        }
    }

    // ---------- gráficos GS ( L / GS 8 L ----------
    private fun graphics(c: Cur, len: Int) {
        if (len < 2) { c.skip(len); return }
        val data = c.bytes(len)
        val fn = data[1].toInt() and 0xFF
        fun u(i: Int) = data[i].toInt() and 0xFF
        when (fn) {
            2, 50 -> {
                desc = "GS ( L · imprimir gráfico del búfer"
                if (gfxBuffer.isNotEmpty()) {
                    val first = gfxBuffer[0].first
                    placeBlock(first.w * gfxScaleX, first.h * gfxScaleY) { x, top ->
                        gfxBuffer.map { (m, isRed) -> val s = m.scaled(gfxScaleX, gfxScaleY); ImageItem(x, top, s.w, s.h, s.bits, isRed) }
                    }
                    gfxBuffer = ArrayList()
                }
            }
            112, 113 -> {
                // m fn a bx by c xL xH yL yH datos
                val bx = u(3); val by = u(4); val color = u(5)
                val w = u(6) or (u(7) shl 8); val h = u(8) or (u(9) shl 8)
                val bits = data.copyOfRange(10, data.size)
                val img = if (fn == 112) rasterMatrix(bits, w, h) else columnImage(bits, w, h)
                if (color == 49 || gfxBuffer.isEmpty()) gfxBuffer = ArrayList()
                gfxBuffer.add(img to (color == 50))
                gfxScaleX = if (bx == 2) 2 else 1; gfxScaleY = if (by == 2) 2 else 1
                desc = "GS ( L · guardar gráfico ${w}×$h en el búfer"
            }
            67, 68 -> {
                // m fn a kc1 kc2 b xL xH yL yH [c datos]
                val key = (u(3) shl 8) or u(4)
                val w = u(6) or (u(7) shl 8); val h = u(8) or (u(9) shl 8)
                val bits = data.copyOfRange(11.coerceAtMost(data.size), data.size)
                nvGraphics[key] = if (fn == 67) rasterMatrix(bits, w, h) else columnImage(bits, w, h)
                desc = "GS ( L · definir gráfico NV \"${u(3).toChar()}${u(4).toChar()}\" ${w}×$h"
            }
            69 -> {
                val key = (u(2) shl 8) or u(3)
                val sx = if (data.size > 4 && u(4) == 2) 2 else 1
                val sy = if (data.size > 5 && u(5) == 2) 2 else 1
                val img = nvGraphics[key]
                if (img != null) block(img.scaled(sx, sy), false) else note("[Gráfico NV \"${u(2).toChar()}${u(3).toChar()}\" no definido]")
                desc = "GS ( L · imprimir gráfico NV \"${u(2).toChar()}${u(3).toChar()}\""
            }
            else -> desc = "GS ( L · función $fn"
        }
    }

    // ---------- imágenes ----------
    private fun escImage(c: Cur) {
        val m = c.u8()
        val cols = c.u16()
        val bytesPerCol = if (m >= 32) 3 else 1
        val data = c.bytes(cols * bytesPerCol)
        val dotsV = bytesPerCol * 8
        val sx = if (m == 0 || m == 32) 2 else 1
        val sy = if (m < 32) 3 else 1
        val base = ByteArray(cols * dotsV)
        for (col in 0 until cols) for (k in 0 until bytesPerCol) {
            val v = data[col * bytesPerCol + k].toInt()
            for (bit in 0 until 8) if (v and (0x80 shr bit) != 0) base[(k * 8 + bit) * cols + col] = 1
        }
        val img = Matrix(cols, dotsV, base).scaled(sx, sy)
        val st = ImageItem(0, 0, img.w, img.h, img.bits, red)
        pending.add(Pending(lineX, img.w, img.h, false) { x, top -> listOf(ImageItem(x, top, st.w, st.h, st.pixels, st.red)) })
        lineX += img.w
        desc = "ESC * · imagen de bits ${cols}×$dotsV (modo $m)"
    }

    private fun rasterImage(c: Cur) {
        val sub = c.u8()
        if (sub != '0'.code) { desc = "GS v ${sub.toChar()}"; return }
        val m = c.u8().let { if (it >= 48) it - 48 else it }
        val xb = c.u16(); val h = c.u16()
        val data = c.bytes(xb * h)
        val img = rasterMatrix(data, xb * 8, h).scaled(if (m and 1 != 0) 2 else 1, if (m and 2 != 0) 2 else 1)
        block(img, red)
        desc = "GS v 0 · imagen raster ${xb * 8}×$h"
    }

    private fun rasterMatrix(data: ByteArray, w: Int, h: Int): Matrix {
        val rowBytes = (w + 7) / 8
        val out = ByteArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            val i = yy * rowBytes + xx / 8
            if (i < data.size && (data[i].toInt() and (0x80 shr (xx % 8))) != 0) out[yy * w + xx] = 1
        }
        return Matrix(w, h, out)
    }

    /** Formato por columnas (FS q, GS *): cada columna son h/8 bytes, bit alto arriba. */
    private fun columnImage(data: ByteArray, w: Int, h: Int): Matrix {
        val colBytes = (h + 7) / 8
        val out = ByteArray(w * h)
        for (xx in 0 until w) for (yy in 0 until h) {
            val i = xx * colBytes + yy / 8
            if (i < data.size && (data[i].toInt() and (0x80 shr (yy % 8))) != 0) out[yy * w + xx] = 1
        }
        return Matrix(w, h, out)
    }

    // ---------- códigos de barras ----------
    private fun barcode(c: Cur) {
        val m = c.u8()
        val data: ByteArray = if (m <= 6) {
            val start = c.p
            while (c.peek() != 0) c.p++
            val d = c.buf.copyOfRange(start, c.p)
            c.u8()
            d
        } else c.bytes(c.u8())
        desc = "GS k · código de barras «${String(data, Charsets.ISO_8859_1)}»"
        try {
            val (row, hri) = Codes.barcode(m, data)
            val w = row.size * barcodeModule
            val h = barcodeHeight
            val pix = ByteArray(w * h)
            for (xx in 0 until w) if (row[xx / barcodeModule].toInt() == 1) for (yy in 0 until h) pix[yy * w + xx] = 1
            val cw = if (hriFont == 0) 12 else 9
            val ch = if (hriFont == 0) 24 else 17
            val above = hriPos == 1 || hriPos == 3
            val below = hriPos == 2 || hriPos == 3
            val total = h + (if (above) ch + 4 else 0) + (if (below) ch + 4 else 0)
            val width = maxOf(w, hri.length * cw)
            placeBlock(width, total) { x, top ->
                val list = ArrayList<Item>()
                var yy = top
                val tx = x + (width - hri.length * cw) / 2
                if (above) { list.add(TextItem(tx, yy, hri, cw, ch, 0, false, 0, false, false, false)); yy += ch + 4 }
                list.add(ImageItem(x + (width - w) / 2, yy, w, h, pix, red))
                yy += h + 4
                if (below) list.add(TextItem(tx, yy, hri, cw, ch, 0, false, 0, false, false, false))
                list
            }
        } catch (e: Exception) {
            note("[Código de barras no válido: ${e.message ?: "datos incorrectos"}]")
            desc += " · ERROR: ${e.message}"
        }
    }

    // =====================================================================
    // Maquetación
    // =====================================================================

    private class Pending(val x: Int, var w: Int, val h: Int, val isText: Boolean, val make: (Int, Int) -> List<Item>) {
        var text: StringBuilder? = null
        var style: Style? = null
    }

    private data class Style(
        val cellW: Int, val cellH: Int, val spacing: Int, val bold: Boolean, val underline: Int,
        val reverse: Boolean, val red: Boolean, val upsideDown: Boolean,
    )

    private fun area(): Int = minOf(areaWidth, config.paperDots - leftMargin).coerceAtLeast(48)

    private fun baseW() = if (font == 0) 12 else 9
    private fun baseH() = if (font == 0) 24 else 17
    private fun advance() = baseW() * scaleX + charSpacing * scaleX

    private fun putChar(ch: Char, widthUnits: Int) {
        val st = Style(baseW() * scaleX * widthUnits, baseH() * scaleY, charSpacing * scaleX, bold, underline, reverse, red, upsideDown)
        val adv = st.cellW + st.spacing
        if (lineX + adv > area() && lineX > 0) printLine()
        val last = pending.lastOrNull()
        if (last != null && last.isText && last.style == st && last.x + last.w == lineX) {
            last.text!!.append(ch)
            last.w += adv
        } else {
            val p = Pending(lineX, adv, st.cellH, true) { _, _ -> emptyList() }
            p.text = StringBuilder().append(ch)
            p.style = st
            pending.add(p)
        }
        lineX += adv
    }

    private fun tab() {
        val next = tabs.firstOrNull { it > lineX } ?: return
        if (next > area()) return
        // Los espacios de tabulación se ven en modo inverso/subrayado, como en una impresora real.
        if (reverse || underline > 0) {
            val p = Pending(lineX, next - lineX, baseH() * scaleY, false) { x, top ->
                listOf(TextItem(x, top, " ".repeat(((next - lineX) / baseW()).coerceAtLeast(1)), baseW(), baseH() * scaleY, 0, false, underline, reverse, red, false))
            }
            pending.add(p)
        }
        lineX = next
    }

    /** Imprime la línea pendiente y avanza el papel. [feed] < 0 = interlineado actual. */
    private fun printLine(feed: Int = -1) {
        val adv = if (feed >= 0) feed else lineSpacing
        if (pending.isEmpty()) {
            y += adv
            lineX = 0
            return
        }
        val maxH = pending.maxOf { it.h }
        val used = pending.maxOf { it.x + it.w }
        val offset = when (align) {
            1 -> (area() - used) / 2
            2 -> area() - used
            else -> 0
        }.coerceAtLeast(0)
        val base = leftMargin + offset
        for (p in pending) {
            val top = y + (maxH - p.h)
            if (p.isText) {
                val st = p.style!!
                add(TextItem(base + p.x, top, p.text.toString(), st.cellW, st.cellH, st.spacing, st.bold, st.underline, st.reverse, st.red, st.upsideDown))
            } else {
                for (it in p.make(base + p.x, top)) add(it)
            }
        }
        y += maxOf(adv, maxH)
        pending.clear()
        lineX = 0
    }

    private fun add(item: Item) {
        if (item.y + item.h > Ticket.MAX_HEIGHT) {
            ticket.truncated = true
            return
        }
        ticket.items.add(item)
    }

    private fun block(m: Matrix, isRed: Boolean) {
        placeBlock(m.w, m.h) { x, top -> listOf(ImageItem(x, top, m.w, m.h, m.bits, isRed)) }
    }

    /** Coloca un bloque (código, imagen raster…) en su propia franja, respetando la alineación. */
    private fun placeBlock(w: Int, h: Int, make: (Int, Int) -> List<Item>) {
        if (pending.isNotEmpty()) printLine(0)
        val a = area()
        val offset = when (align) {
            1 -> (a - w) / 2
            2 -> a - w
            else -> 0
        }.coerceAtLeast(0)
        for (it in make(leftMargin + offset, y)) add(it)
        y += h
        lineX = 0
    }

    private fun note(text: String) {
        if (pending.isNotEmpty()) printLine(0)
        val h = 26
        add(NoteItem(0, y, config.paperDots, h, text))
        y += h + 4
    }

    private fun event(msg: String) {
        ticket.events.add(msg)
        listener.onEvent(msg)
    }

    private fun drawer(pin: Int, ms: Int) {
        val p = if (pin == 0 || pin == 48) 2 else 5
        event("Apertura de cajón (pin $p, $ms ms)")
        desc = "Pulso de apertura del cajón portamonedas (pin $p)"
    }

    private fun finishTicket(cut: Int, source: String) {
        val t = ticket
        if (t.isEmpty) {
            // Nada visible: conservamos los bytes para el siguiente ticket solo si no hubo corte.
            if (cut > 0) {
                t.raw.reset(); t.log.clear()
            }
            y = 0
            return
        }
        t.cut = cut
        t.height = (y + 24).coerceAtMost(Ticket.MAX_HEIGHT)
        ticket = newTicket(source)
        y = 0
        listener.onTicket(t)
    }

    private fun paperSensor(): Int =
        (if (status.paperNearEnd) 0x03 else 0) or (if (status.paperOut) 0x0C else 0)

    private fun asbBytes(): ByteArray {
        val s = status
        val b1 = 0x10 or (if (s.drawerOpen) 0x04 else 0) or (if (s.isOffline) 0x08 else 0) or (if (s.coverOpen) 0x20 else 0)
        val b2 = if (s.paperOut) 0x20 else 0
        val b3 = paperSensor()
        return byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), 0)
    }

    private fun modeDesc(): String {
        val parts = ArrayList<String>()
        parts.add(if (font == 0) "fuente A" else "fuente B")
        if (bold) parts.add("negrita")
        if (scaleX > 1) parts.add("doble ancho")
        if (scaleY > 1) parts.add("doble alto")
        if (underline > 0) parts.add("subrayado")
        return " (" + parts.joinToString(", ") + ")"
    }

    private fun onOff(b: Boolean) = if (b) "sí" else "no"

    private fun hexShort(buf: ByteArray, from: Int, to: Int): String {
        val n = minOf(to - from, 8)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(String.format("%02X ", buf[from + i].toInt() and 0xFF))
        if (to - from > n) sb.append("…")
        return sb.toString().trimEnd()
    }

    companion object {
        const val DEFAULT_LINE_SPACING = 30
        const val MAX_COMMAND = 16 * 1024 * 1024

        /** Juegos de caracteres internacionales de ESC R (solo los cambios respecto a EE.UU.). */
        private val INTL: Array<Map<Char, Char>?> = Array(18) { null }

        init {
            fun m(vararg p: Pair<Int, Char>) = p.associate { it.first.toChar() to it.second }
            INTL[1] = m(0x23 to '#', 0x40 to 'à', 0x5B to '°', 0x5C to 'ç', 0x5D to '§', 0x7B to 'é', 0x7C to 'ù', 0x7D to 'è', 0x7E to '¨') // Francia
            INTL[2] = m(0x40 to '§', 0x5B to 'Ä', 0x5C to 'Ö', 0x5D to 'Ü', 0x7B to 'ä', 0x7C to 'ö', 0x7D to 'ü', 0x7E to 'ß') // Alemania
            INTL[3] = m(0x23 to '£') // Reino Unido
            INTL[6] = m(0x5B to '°', 0x5D to 'é', 0x60 to 'ù', 0x7B to 'à', 0x7C to 'ò', 0x7D to 'è', 0x7E to 'ì') // Italia
            INTL[7] = m(0x23 to '₧', 0x5B to '¡', 0x5C to 'Ñ', 0x5D to '¿', 0x7B to '¨', 0x7C to 'ñ') // España I
            INTL[11] = m(0x40 to 'á', 0x5B to '¡', 0x5C to 'Ñ', 0x5D to '¿', 0x5E to 'é', 0x7B to 'í', 0x7C to 'ñ', 0x7D to 'ó', 0x7E to 'ú') // España II
            INTL[12] = m(0x40 to 'á', 0x5B to '¡', 0x5C to 'Ñ', 0x5D to '¿', 0x5E to 'é', 0x60 to 'ü', 0x7B to 'í', 0x7C to 'ñ', 0x7D to 'ó', 0x7E to 'ú') // Latinoamérica
        }
    }
}
