package com.copidev.escposvirtual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosPrinterTest {
    private class Capture : PrinterListener, Host {
        val tickets = ArrayList<Ticket>()
        val replies = ArrayList<ByteArray>()
        override fun onTicket(ticket: Ticket) { tickets.add(ticket) }
        override fun onEvent(message: String) {}
        override val name = "test"
        override var asbEnabled = false
        override fun reply(bytes: ByteArray) { replies.add(bytes) }
    }

    /** Envía los datos en trozos pequeños para comprobar el análisis incremental. */
    private fun feed(p: EscPosPrinter, host: Host, data: ByteArray, chunk: Int) {
        var buf = ByteArray(0)
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            buf += data.copyOfRange(i, i + n)
            i += n
            val used = p.process(buf, buf.size, host)
            buf = buf.copyOfRange(used, buf.size)
        }
        assertEquals(0, buf.size)
        p.endOfJob(host)
    }

    @Test
    fun sampleTicketRendersInAnyChunkSize() {
        for (chunk in listOf(1, 3, 7, 64, 100000)) {
            val c = Capture()
            val p = EscPosPrinter(PrinterConfig(), PrinterStatus(), c)
            feed(p, c, SampleTicket.bytes(), chunk)
            assertEquals("chunk $chunk", 1, c.tickets.size)
            val t = c.tickets[0]
            assertEquals(1, t.cut)
            assertTrue(t.log.none { it.contains("Error") || it.contains("ERROR") || it.contains("desconocido") })
            assertTrue(t.items.any { it is ImageItem && it.w == it.h }) // el QR
            val text = t.items.filterIsInstance<TextItem>().joinToString("") { it.text }
            assertTrue(text.contains("Cañería Ñandú · 12,50 €"))
            assertTrue(text.contains("8412345678905")) // HRI del EAN-13 con dígito de control
        }
    }

    @Test
    fun statusQueriesAreAnswered() {
        val c = Capture()
        val st = PrinterStatus().apply { paperOut = true; drawerOpen = true }
        val p = EscPosPrinter(PrinterConfig(), st, c)
        p.process(byteArrayOf(0x10, 0x04, 1, 0x10, 0x04, 4, 0x1D, 0x72, 1), 9, c)
        assertEquals(listOf(0x16 or 0x04 or 0x08, 0x12 or 0x60, 0x0C), c.replies.map { it[0].toInt() and 0xFF })
    }

    @Test
    fun cutSplitsTickets() {
        val c = Capture()
        val p = EscPosPrinter(PrinterConfig(), PrinterStatus(), c)
        val data = "uno\n".toByteArray() + byteArrayOf(0x1D, 0x56, 0) + "dos\n".toByteArray() + byteArrayOf(0x1D, 0x56, 1)
        feed(p, c, data, 1000)
        assertEquals(2, c.tickets.size)
        assertEquals(2, c.tickets[0].cut)
        assertEquals(1, c.tickets[1].cut)
    }

    @Test
    fun wrapsLongLinesAt48Columns() {
        val c = Capture()
        val p = EscPosPrinter(PrinterConfig(paperDots = 576), PrinterStatus(), c)
        feed(p, c, ("x".repeat(50) + "\n").toByteArray(), 1000)
        val rows = c.tickets[0].items.filterIsInstance<TextItem>().map { it.y }.distinct()
        assertEquals(2, rows.size)
    }
}
