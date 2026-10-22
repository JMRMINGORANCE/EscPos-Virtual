package com.copidev.escposvirtual

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.copidev.escposvirtual.core.EscPosPrinter
import com.copidev.escposvirtual.core.Host
import com.copidev.escposvirtual.core.PrinterListener
import com.copidev.escposvirtual.core.PrinterStatus
import com.copidev.escposvirtual.core.Ticket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/** Estado global de la app: impresora emulada, servidor TCP y lista de tickets recibidos. */
object PrinterHub {
    interface Listener {
        fun onTicketsChanged() {}
        fun onServerChanged() {}
        fun onEvent(message: String) {}
    }

    const val MAX_TICKETS = 100

    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set
    lateinit var printer: EscPosPrinter
        private set
    val status = PrinterStatus()
    /** Tickets recibidos, el más reciente primero. Solo se toca desde el hilo principal. */
    val tickets = ArrayList<Ticket>()
    val listeners = CopyOnWriteArraySet<Listener>()

    var server: TcpServer? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var tone: ToneGenerator? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        printer = EscPosPrinter(prefs.printerConfig(), status, object : PrinterListener {
            override fun onTicket(ticket: Ticket) {
                main.post {
                    tickets.add(0, ticket)
                    while (tickets.size > MAX_TICKETS) tickets.removeAt(tickets.size - 1)
                    if (prefs.sound) beep()
                    listeners.forEach { it.onTicketsChanged() }
                }
            }

            override fun onEvent(message: String) {
                main.post { listeners.forEach { it.onEvent(message) } }
            }
        })
    }

    val isRunning: Boolean get() = server?.running == true

    @Synchronized
    fun startServer() {
        if (server?.running == true) return
        server?.stop()
        val s = TcpServer(prefs.port, { printer }) { main.post { serverChanged() } }
        server = s
        s.start()
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        unregisterNsd()
        main.post { listeners.forEach { it.onServerChanged() } }
    }

    private var nsdRegisteredPort = -1

    private fun serverChanged() {
        val s = server
        if (s != null && s.running && nsdRegisteredPort != s.port) registerNsd(s.port)
        if (s == null || !s.running) unregisterNsd()
        listeners.forEach { it.onServerChanged() }
    }

    fun applySettings() {
        printer.configChanged(prefs.printerConfig())
        val s = server
        if (s != null && s.port != prefs.port && (s.running || s.error != null)) {
            s.stop()
            unregisterNsd()
            thread { Thread.sleep(300); main.post { startServer() } }
        }
        listeners.forEach { it.onTicketsChanged() }
    }

    fun statusChanged() {
        thread { printer.statusChanged() }
    }

    fun clear() {
        tickets.clear()
        listeners.forEach { it.onTicketsChanged() }
    }

    /** Procesa un archivo .bin/.prn como si llegara por la red. */
    fun feed(bytes: ByteArray, name: String) {
        thread {
            val host = object : Host {
                override val name = name
                override var asbEnabled = false
                override fun reply(bytes: ByteArray) {}
            }
            printer.process(bytes, bytes.size, host)
            printer.endOfJob(host)
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (_: Exception) {
        }
    }

    /** Direcciones IPv4 del dispositivo (interfaz, IP). */
    fun addresses(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address) out.add(ni.name to (a.hostAddress ?: continue))
            }
        } catch (_: Exception) {
        }
        // wlan primero, que es la que suele interesar
        return out.sortedBy { if (it.first.startsWith("wlan")) 0 else if (it.first.startsWith("eth")) 1 else 2 }
    }

    private fun registerNsd(port: Int) {
        unregisterNsd()
        try {
            val mgr = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Impresora ESC-POS Virtual"
                serviceType = "_pdl-datastream._tcp"
                setPort(port)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = mgr
            nsdListener = l
            nsdRegisteredPort = port
        } catch (_: Exception) {
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        runCatching { nsd?.unregisterService(l) }
        nsdListener = null
        nsdRegisteredPort = -1
    }
}
