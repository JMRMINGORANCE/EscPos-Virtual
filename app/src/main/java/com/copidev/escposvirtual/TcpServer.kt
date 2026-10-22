package com.copidev.escposvirtual

import com.copidev.escposvirtual.core.EscPosPrinter
import com.copidev.escposvirtual.core.Host
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Servidor TCP "RAW" (puerto 9100 por defecto) como el de una impresora de red. */
class TcpServer(
    val port: Int,
    private val printer: () -> EscPosPrinter,
    private val onChange: () -> Unit,
) {
    @Volatile var running = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile private var server: ServerSocket? = null
    val clients = CopyOnWriteArrayList<Client>()

    fun start() {
        thread(name = "escpos-accept", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                running = true
                error = null
                onChange()
                while (running) {
                    val s = ss.accept()
                    val c = Client(s)
                    clients.add(c)
                    onChange()
                    thread(name = "escpos-client", isDaemon = true) { c.run() }
                }
            } catch (e: IOException) {
                if (running || server == null) error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
                runCatching { server?.close() }
                onChange()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        for (c in clients) c.close()
    }

    inner class Client(private val socket: Socket) : Host {
        override val name: String = socket.inetAddress?.hostAddress ?: "?"
        @Volatile override var asbEnabled = false
        private val out: OutputStream = socket.getOutputStream()

        override fun reply(bytes: ByteArray) {
            synchronized(out) {
                try {
                    out.write(bytes)
                    out.flush()
                } catch (_: IOException) {
                }
            }
        }

        fun run() {
            val p = printer()
            var buf = ByteArray(64 * 1024)
            var len = 0
            var dirty = false
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = IDLE_MS
                val input = socket.getInputStream()
                while (true) {
                    if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                    val n = try {
                        input.read(buf, len, buf.size - len)
                    } catch (e: SocketTimeoutException) {
                        if (dirty) { p.idle(this); dirty = false }
                        continue
                    }
                    if (n < 0) break
                    len += n
                    dirty = true
                    val used = p.process(buf, len, this)
                    if (used > 0) {
                        System.arraycopy(buf, used, buf, 0, len - used)
                        len -= used
                    }
                }
            } catch (_: IOException) {
            } finally {
                p.endOfJob(this)
                close()
                clients.remove(this)
                onChange()
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Sin datos durante este tiempo con la conexión abierta → se cierra el ticket en curso. */
        const val IDLE_MS = 1200
    }
}
