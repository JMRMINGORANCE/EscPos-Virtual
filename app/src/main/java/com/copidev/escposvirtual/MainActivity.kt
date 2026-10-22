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
import android.graphics.drawable.GradientDrawable
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
import android.widget.Switch
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

class MainActivity : Activity(), PrinterHub.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val renderPool = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var serverSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var otherText: TextView
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private val adapter = TicketAdapter()
    private val refresher = object : Runnable {
        override fun run() {
            updateServerViews()
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
        if (PrinterHub.prefs.autoStart && !PrinterHub.isRunning) PrinterService.start(this)
    }

    private var crashMode = false

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

    override fun onStart() {
        super.onStart()
        if (crashMode) return
        PrinterHub.listeners.add(this)
        adapter.notifyDataSetChanged()
        main.post(refresher)
    }

    override fun onStop() {
        if (crashMode) { super.onStop(); return }
        PrinterHub.listeners.remove(this)
        main.removeCallbacks(refresher)
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

    override fun onServerChanged() = updateServerViews()

    override fun onEvent(message: String) {
        Toast.makeText(this, "🔔 $message", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openFile(it) }
    }

    // ------------------------------------------------------------------ interfaz

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun tv(text: String = "", sp: Float = 14f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun flatButton(text: String, color: Int = ACCENT, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(color)
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Cabecera
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HEADER)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(tv(getString(R.string.app_name), 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        serverSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on ->
                if (on == PrinterHub.isRunning) return@setOnCheckedChangeListener
                PrinterHub.prefs.autoStart = on
                if (on) PrinterService.start(this@MainActivity) else PrinterService.stop(this@MainActivity)
            }
        }
        titleRow.addView(serverSwitch)
        header.addView(titleRow)
        statusText = tv("", 13f, 0xFFB0BEC5.toInt())
        header.addView(statusText)
        addressText = tv("", 26f, Color.WHITE, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(2))
        }
        header.addView(addressText)
        otherText = tv("", 12f, 0xFF90A4AE.toInt())
        header.addView(otherText)
        root.addView(header)

        // Barra de acciones
        val actions = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
        }
        actions.addView(flatButton("Ticket de prueba") { PrinterHub.feed(SampleTicket.bytes(PrinterHub.prefs.printerConfig().paperDots), "prueba") })
        actions.addView(flatButton("Abrir .bin") { pickFile() })
        actions.addView(flatButton("Ajustes") { showSettings() })
        actions.addView(flatButton("Borrar") { cache.evictAll(); PrinterHub.clear() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })

        // Simulación de estado
        val sim = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        sim.addView(tv("Simular:", 13f, MUTED))
        val st = PrinterHub.status
        fun simBox(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            text = label
            textSize = 13f
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); PrinterHub.statusChanged() }
        }
        sim.addView(simBox("Sin papel", { st.paperOut }) { st.paperOut = it })
        sim.addView(simBox("Poco papel", { st.paperNearEnd }) { st.paperNearEnd = it })
        sim.addView(simBox("Tapa abierta", { st.coverOpen }) { st.coverOpen = it })
        sim.addView(simBox("Cajón abierto", { st.drawerOpen }) { st.drawerOpen = it })
        sim.addView(simBox("Offline", { st.offline }) { st.offline = it })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sim) })
        root.addView(View(this).apply { setBackgroundColor(0xFFDDDDDD.toInt()) }, LinearLayout.LayoutParams(-1, 1))

        // Lista de tickets
        val listFrame = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(24))
        }
        emptyView = tv("", 15f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setLineSpacing(0f, 1.3f)
        }
        listFrame.addView(list, FrameLayout.LayoutParams(-1, -1))
        listFrame.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = emptyView
        root.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        rootView = root
        headerView = header
        updateServerViews()
        return root
    }

    private lateinit var rootView: LinearLayout
    private lateinit var headerView: LinearLayout

    /** Se llama después de setContentView: antes no existe la DecorView y getInsetsController() falla. */
    private fun setupSystemBars() {
        val root = rootView
        val header = headerView
        // Bordes de pantalla (Android 15 obliga a dibujar bajo las barras del sistema)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
            root.setOnApplyWindowInsetsListener { _, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                header.setPadding(dp(16) + i.left, dp(12) + i.top, dp(16) + i.right, dp(14))
                root.setPadding(0, 0, 0, i.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            window.statusBarColor = HEADER
        }
    }

    private fun updateServerViews() {
        val server = PrinterHub.server
        val port = PrinterHub.prefs.port
        val running = PrinterHub.isRunning
        serverSwitch.isChecked = running
        val addrs = PrinterHub.addresses()
        val conns = server?.clients?.size ?: 0
        statusText.text = when {
            running -> "● Escuchando" + if (conns > 0) " · $conns conexión" + (if (conns > 1) "es" else "") + " abierta" + (if (conns > 1) "s" else "") else ""
            server?.error != null -> "✕ No se pudo abrir el puerto $port: ${server.error}"
            else -> "○ Detenida"
        }
        statusText.setTextColor(
            when {
                running -> 0xFF69F0AE.toInt()
                server?.error != null -> 0xFFFF8A80.toInt()
                else -> 0xFFB0BEC5.toInt()
            }
        )
        val main = addrs.firstOrNull()
        addressText.text = if (main != null) "${main.second}:$port" else "Sin red · 127.0.0.1:$port"
        addressText.alpha = if (running) 1f else 0.5f
        val rest = addrs.drop(1).joinToString("  ·  ") { "${it.first} ${it.second}" }
        otherText.text = (if (rest.isNotEmpty()) "$rest\n" else "") +
            "Desde este mismo móvil: 127.0.0.1:$port · Papel ${if (PrinterHub.prefs.paper80) "80" else "58"} mm"
        emptyView.text = if (running)
            "Configura tu app para imprimir en una impresora de red (RAW / ESC/POS) con:\n\n${addressText.text}\n\nCada corte de papel o cierre de conexión crea un ticket nuevo."
        else "La impresora está detenida. Actívala con el interruptor de arriba."
    }

    private fun applyScreenOn() {
        if (PrinterHub.prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                updateServerViews()
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

    // ------------------------------------------------------------------ lista

    private class Holder(
        val title: TextView,
        val subtitle: TextView,
        val image: TicketImageView,
        val events: TextView,
        val log: Button,
        val hex: Button,
        val share: Button,
    ) {
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
            h.title.text = "Ticket #${t.id} · ${timeFmt.format(Date(t.createdAt))}"
            val cut = when (t.cut) { 2 -> "corte total"; 1 -> "corte parcial"; else -> "sin corte" }
            h.subtitle.text = "${t.source} · ${String.format(Locale.getDefault(), "%,d", t.raw.size())} bytes · $cut · ${if (t.paperDots >= 576) "80" else "58"} mm"
            h.events.visibility = if (t.events.isEmpty()) View.GONE else View.VISIBLE
            h.events.text = t.events.joinToString("\n") { "🔔 $it" }
            h.log.setOnClickListener { showText("Comandos · ticket #${t.id}", if (t.log.isEmpty()) "(sin comandos)" else t.log.joinToString("\n")) }
            h.hex.setOnClickListener { showText("Hex · ticket #${t.id}", t.hexDump(64 * 1024)) }
            h.share.setOnClickListener { share(t) }
            h.image.ratio = (t.height + TicketRenderer.MARGIN * 2 + 28).toFloat() / (t.paperDots + TicketRenderer.MARGIN * 2)
            h.image.paperDots = t.paperDots
            val bmp = cache.get(t.id)
            if (bmp != null) {
                h.image.setImageBitmap(bmp)
                h.image.ratio = bmp.height.toFloat() / bmp.width
            } else {
                h.image.setImageDrawable(null)
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
            }
            h.image.requestLayout()
            return view
        }

        private fun createItem(): View {
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(6))
                layoutParams = AbsListView.LayoutParams(-1, -2)
            }
            val title = tv("", 15f, INK, true)
            val subtitle = tv("", 12f, MUTED)
            item.addView(title)
            item.addView(subtitle)
            val frame = FrameLayout(this@MainActivity).apply { setPadding(0, dp(8), 0, dp(4)) }
            val image = TicketImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), 0xFFD0D0D0.toInt()) }
                elevation = dp(2).toFloat()
                maxWidthPx = dp(460)
            }
            frame.addView(image, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            item.addView(frame)
            val events = tv("", 13f, 0xFFE65100.toInt()).apply { setPadding(0, dp(4), 0, 0) }
            item.addView(events)
            val buttons = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val log = flatButton("Comandos") {}
            val hex = flatButton("Hex") {}
            val share = flatButton("Compartir") {}
            buttons.addView(log); buttons.addView(hex); buttons.addView(share)
            item.addView(buttons)
            item.tag = Holder(title, subtitle, image, events, log, hex, share)
            return item
        }
    }

    companion object {
        private const val REQ_OPEN = 7
        private const val BG = 0xFFF1F3F5.toInt()
        private const val HEADER = 0xFF1F2A30.toInt()
        private const val INK = 0xFF212529.toInt()
        private const val MUTED = 0xFF6C757D.toInt()
        private const val ACCENT = 0xFF00796B.toInt()
    }
}

/** ImageView que ocupa todo el ancho disponible (hasta [maxWidthPx]) manteniendo la proporción del ticket. */
class TicketImageView(context: Context) : ImageView(context) {
    var ratio = 1.5f
    var maxWidthPx = Int.MAX_VALUE
    var paperDots = 576

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        // A 58 mm el ticket es más estrecho, igual que en la vida real.
        val limit = if (paperDots < 576) (maxWidthPx * 0.72f).toInt() else maxWidthPx
        val maxByPaper = if (paperDots < 576) (avail * 0.72f).toInt() else avail
        val w = minOf(maxByPaper, limit).coerceAtLeast(1)
        setMeasuredDimension(w, (w * ratio).toInt().coerceAtLeast(1))
    }
}
