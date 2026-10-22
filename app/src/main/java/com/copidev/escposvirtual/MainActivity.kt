package com.copidev.escposvirtual

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.copidev.escposvirtual.core.CodePage
import com.copidev.escposvirtual.core.SampleTicket
import com.copidev.escposvirtual.core.Ticket
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Pantalla única: solo muestra los tickets. Todas las opciones están en el menú que aparece
 * al mantener pulsada la pantalla.
 */
class MainActivity : Activity(), PrinterHub.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val renderPool = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var crashMode = false
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private val adapter = TicketAdapter()
    private val refresher = object : Runnable {
        override fun run() {
            updateEmptyView()
            main.postDelayed(this, 4000)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = App.crashFile(this)
        if (crash.exists()) {
            showCrash(crash.readText())
            return
        }
        PrinterHub.init(this)
        setContentView(buildUi())
        setupSystemBars()
        applyScreenOn()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (PrinterHub.prefs.autoStart && !PrinterHub.isRunning) startServer()
    }

    override fun onStart() {
        super.onStart()
        if (crashMode) return
        PrinterHub.listeners.add(this)
        adapter.notifyDataSetChanged()
        main.post(refresher)
    }

    override fun onStop() {
        if (!crashMode) {
            PrinterHub.listeners.remove(this)
            main.removeCallbacks(refresher)
        }
        super.onStop()
    }

    override fun onDestroy() {
        renderPool.shutdownNow()
        super.onDestroy()
    }

    override fun onTicketsChanged() {
        adapter.notifyDataSetChanged()
        if (PrinterHub.tickets.isNotEmpty()) list.setSelection(0)
    }

    override fun onServerChanged() = updateEmptyView()

    override fun onEvent(message: String) {
        Toast.makeText(this, "🔔 $message", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openFile(it) }
    }

    private fun startServer() {
        try {
            PrinterService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar el servidor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ interfaz

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun tv(text: String = "", sp: Float = 14f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        list = ListView(this).apply {
            divider = null
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(24))
            isLongClickable = true
            setOnItemLongClickListener { _, _, position, _ ->
                ticketMenu(PrinterHub.tickets[position]); true
            }
        }
        emptyView = tv("", 15f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setLineSpacing(0f, 1.3f)
            setOnLongClickListener { mainMenu(); true }
            setOnClickListener { mainMenu() }
        }
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = emptyView
        updateEmptyView()
        return root
    }

    /** Debe llamarse después de setContentView (antes no existe la DecorView). */
    private fun setupSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ dibuja siempre bajo las barras del sistema: dejamos hueco.
            val content = findViewById<View>(android.R.id.content)
            content.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }
    }

    private fun updateEmptyView() {
        if (!::emptyView.isInitialized) return
        val port = PrinterHub.prefs.port
        val server = PrinterHub.server
        val ip = PrinterHub.addresses().firstOrNull()?.second ?: "127.0.0.1"
        emptyView.text = when {
            PrinterHub.isRunning -> "Esperando tickets en\n\n$ip:$port\n\nMantén pulsada la pantalla para ver las opciones."
            server?.error != null -> "No se pudo abrir el puerto $port:\n${server.error}\n\nToca para ver las opciones."
            else -> "La impresora está detenida.\n\nToca para ver las opciones."
        }
    }

    private fun applyScreenOn() {
        if (PrinterHub.prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ menús

    private fun ticketMenu(t: Ticket) {
        val cut = when (t.cut) { 2 -> "corte total"; 1 -> "corte parcial"; else -> "sin corte" }
        val title = "Ticket #${t.id} · ${timeFmt.format(Date(t.createdAt))}\n" +
            "${t.source} · ${String.format(Locale.getDefault(), "%,d", t.raw.size())} bytes · $cut"
        val items = arrayOf("Comandos recibidos", "Volcado hex", "Compartir…", "Más opciones…")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showText("Comandos · ticket #${t.id}", if (t.log.isEmpty()) "(sin comandos)" else t.log.joinToString("\n"))
                    1 -> showText("Hex · ticket #${t.id}", t.hexDump(64 * 1024))
                    2 -> share(t)
                    else -> mainMenu()
                }
            }
            .show()
    }

    private fun mainMenu() {
        val running = PrinterHub.isRunning
        val port = PrinterHub.prefs.port
        val addrs = PrinterHub.addresses()
        val ip = addrs.firstOrNull()?.second ?: "sin red"
        val conns = PrinterHub.server?.clients?.size ?: 0
        val title = if (running) "Escuchando en $ip:$port" + (if (conns > 0) " · $conns conexión(es)" else "") else "Impresora detenida"
        val items = arrayOf(
            "Dirección y estado",
            "Ticket de prueba",
            "Simular estado de la impresora…",
            "Abrir archivo .bin / .prn",
            "Ajustes",
            "Borrar todos los tickets",
            if (running) "Detener impresora" else "Iniciar impresora",
        )
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showInfo()
                    1 -> PrinterHub.feed(SampleTicket.bytes(PrinterHub.prefs.printerConfig().paperDots), "prueba")
                    2 -> simulateDialog()
                    3 -> pickFile()
                    4 -> showSettings()
                    5 -> { cache.evictAll(); PrinterHub.clear() }
                    6 -> {
                        PrinterHub.prefs.autoStart = !running
                        if (running) PrinterService.stop(this) else startServer()
                        main.postDelayed({ updateEmptyView() }, 500)
                    }
                }
            }
            .show()
    }

    private fun showInfo() {
        val port = PrinterHub.prefs.port
        val server = PrinterHub.server
        val sb = StringBuilder()
        sb.append(
            when {
                PrinterHub.isRunning -> "● Escuchando"
                server?.error != null -> "✕ Error: ${server.error}"
                else -> "○ Detenida"
            }
        ).append("\n\n")
        val addrs = PrinterHub.addresses()
        if (addrs.isEmpty()) sb.append("Sin red\n")
        for ((name, ip) in addrs) sb.append("$ip:$port   ($name)\n")
        sb.append("127.0.0.1:$port   (desde este mismo móvil)\n\n")
        sb.append("Conexiones abiertas: ${server?.clients?.size ?: 0}\n")
        sb.append("Papel: ${if (PrinterHub.prefs.paper80) "80 mm · 48 columnas" else "58 mm · 32 columnas"}\n")
        sb.append("Página de códigos inicial: ${CodePage.byId(PrinterHub.prefs.codePage).name}\n")
        sb.append("Tickets en memoria: ${PrinterHub.tickets.size}")
        AlertDialog.Builder(this)
            .setTitle("Dirección y estado")
            .setMessage(sb.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun simulateDialog() {
        val st = PrinterHub.status
        val labels = arrayOf("Sin papel", "Poco papel", "Tapa abierta", "Cajón abierto", "Offline")
        val checked = booleanArrayOf(st.paperOut, st.paperNearEnd, st.coverOpen, st.drawerOpen, st.offline)
        AlertDialog.Builder(this)
            .setTitle("Simular estado")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                when (which) {
                    0 -> st.paperOut = on
                    1 -> st.paperNearEnd = on
                    2 -> st.coverOpen = on
                    3 -> st.drawerOpen = on
                    4 -> st.offline = on
                }
                PrinterHub.statusChanged()
            }
            .setPositiveButton("Hecho", null)
            .show()
    }

    // ------------------------------------------------------------------ ajustes

    private fun showSettings() {
        val p = PrinterHub.prefs
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(s: String) = tv(s, 13f, MUTED).apply { setPadding(0, dp(12), 0, 0) }
        box.addView(label("Puerto TCP"))
        val port = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(p.port.toString())
        }
        box.addView(port)
        box.addView(label("Ancho de papel"))
        val group = RadioGroup(this)
        val r80 = RadioButton(this).apply { text = "80 mm · 576 puntos · 48 columnas"; id = View.generateViewId() }
        val r58 = RadioButton(this).apply { text = "58 mm · 384 puntos · 32 columnas"; id = View.generateViewId() }
        group.addView(r80); group.addView(r58)
        group.check(if (p.paper80) r80.id else r58.id)
        box.addView(group)
        box.addView(label("Página de códigos al encender (ESC t la cambia)"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, CodePage.ALL.map { "${it.id} · ${it.name}" })
            setSelection(CodePage.ALL.indexOfFirst { it.id == p.codePage }.coerceAtLeast(0))
        }
        box.addView(spinner)
        val utf8 = CheckBox(this).apply { text = "Interpretar el texto como UTF-8"; isChecked = p.forceUtf8 }
        box.addView(utf8)
        box.addView(tv("Una impresora real no entiende UTF-8: si tu app lo envía, saldrán caracteres raros. Actívalo solo para leer el contenido.", 12f, MUTED))
        val sound = CheckBox(this).apply { text = "Pitido al recibir un ticket"; isChecked = p.sound }
        box.addView(sound)
        val screen = CheckBox(this).apply { text = "Mantener la pantalla encendida"; isChecked = p.keepScreenOn }
        box.addView(screen)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val newPort = port.text.toString().toIntOrNull()
                if (newPort == null || newPort !in 1024..65535) {
                    Toast.makeText(this, "Puerto no válido (1024–65535)", Toast.LENGTH_LONG).show()
                } else p.port = newPort
                p.paper80 = group.checkedRadioButtonId == r80.id
                p.codePage = CodePage.ALL[spinner.selectedItemPosition.coerceAtLeast(0)].id
                p.forceUtf8 = utf8.isChecked
                p.sound = sound.isChecked
                p.keepScreenOn = screen.isChecked
                applyScreenOn()
                PrinterHub.applySettings()
                updateEmptyView()
            }
            .show()
    }

    // ------------------------------------------------------------------ archivos

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_OPEN)
    }

    private fun openFile(uri: Uri) {
        try {
            var name = "archivo"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            PrinterHub.feed(bytes, name)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share(t: Ticket) {
        AlertDialog.Builder(this)
            .setTitle("Compartir ticket #${t.id}")
            .setItems(arrayOf("Imagen (.png)", "Datos ESC/POS en bruto (.bin)", "Registro de comandos (.txt)")) { _, which ->
                try {
                    val dir = ShareProvider.dir(this)
                    dir.listFiles()?.forEach { it.delete() }
                    val (file, mime) = when (which) {
                        0 -> {
                            val f = File(dir, "ticket_${t.id}.png")
                            val bmp = cache.get(t.id) ?: TicketRenderer.render(t)
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            f to "image/png"
                        }
                        1 -> File(dir, "ticket_${t.id}.bin").apply { writeBytes(t.raw.toByteArray()) } to "application/octet-stream"
                        else -> File(dir, "ticket_${t.id}.txt").apply { writeText(t.log.joinToString("\n")) } to "text/plain"
                    }
                    val uri = ShareProvider.uriFor(this, file)
                    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(file.name, uri)
                    startActivity(Intent.createChooser(send, "Compartir"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showText(title: String, text: String) {
        val view = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(INK)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(HorizontalScrollView(this@MainActivity).apply { addView(view) })
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Copiar") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text))
                Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Pantalla mínima (sin nada que pueda volver a fallar) con el error del último cierre. */
    private fun showCrash(text: String) {
        crashMode = true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        box.addView(tv("La app se cerró inesperadamente. Copia este error y envíaselo a quien te la hizo:", 15f, INK, true))
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            this.text = "Copiar error"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("error", text))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            this.text = "Continuar"
            setOnClickListener {
                App.crashFile(this@MainActivity).delete()
                recreate()
            }
        })
        box.addView(buttons)
        box.addView(TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply { addView(HorizontalScrollView(this@MainActivity).apply { addView(box) }) })
    }

    // ------------------------------------------------------------------ lista: solo los tickets

    private class Holder(val image: TicketImageView) {
        var id = -1
    }

    private inner class TicketAdapter : BaseAdapter() {
        override fun getCount() = PrinterHub.tickets.size
        override fun getItem(position: Int): Ticket = PrinterHub.tickets[position]
        override fun getItemId(position: Int) = PrinterHub.tickets[position].id.toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: createItem()
            val h = view.tag as Holder
            val t = getItem(position)
            h.id = t.id
            h.image.paperDots = t.paperDots
            val bmp = cache.get(t.id)
            if (bmp != null) {
                h.image.ratio = bmp.height.toFloat() / bmp.width
                h.image.setImageBitmap(bmp)
            } else {
                h.image.ratio = (t.height + TicketRenderer.MARGIN * 2 + 28).toFloat() / (t.paperDots + TicketRenderer.MARGIN * 2)
                h.image.setImageDrawable(null)
                try {
                    renderPool.execute {
                        val b = try { TicketRenderer.render(t) } catch (e: Throwable) { null } ?: return@execute
                        main.post {
                            cache.put(t.id, b)
                            if (h.id == t.id) {
                                h.image.ratio = b.height.toFloat() / b.width
                                h.image.setImageBitmap(b)
                                h.image.requestLayout()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            h.image.requestLayout()
            return view
        }

        private fun createItem(): View {
            val frame = FrameLayout(this@MainActivity).apply {
                setPadding(dp(8), dp(6), dp(8), dp(10))
                layoutParams = AbsListView.LayoutParams(-1, -2)
            }
            val image = TicketImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
                elevation = dp(3).toFloat()
            }
            frame.addView(image, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            frame.tag = Holder(image)
            return frame
        }
    }

    companion object {
        private const val REQ_OPEN = 7
        private const val BG = 0xFFE4E6E9.toInt()
        private const val INK = 0xFF212529.toInt()
        private const val MUTED = 0xFF6C757D.toInt()
    }
}

/** ImageView que ocupa todo el ancho disponible manteniendo la proporción del ticket. */
class TicketImageView(context: Context) : ImageView(context) {
    var ratio = 1.5f
    var paperDots = 576

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        // A 58 mm el ticket es más estrecho, igual que en la vida real.
        val w = (if (paperDots < 576) (avail * 0.72f).toInt() else avail).coerceAtLeast(1)
        setMeasuredDimension(w, (w * ratio).toInt().coerceAtLeast(1))
    }
}
