package com.copidev.escposvirtual

import android.content.Context
import com.copidev.escposvirtual.core.PrinterConfig

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 9100)
        set(v) = sp.edit().putInt("port", v).apply()

    /** true = 80 mm (576 puntos), false = 58 mm (384 puntos). */
    var paper80: Boolean
        get() = sp.getBoolean("paper80", true)
        set(v) = sp.edit().putBoolean("paper80", v).apply()

    var codePage: Int
        get() = sp.getInt("codePage", 0)
        set(v) = sp.edit().putInt("codePage", v).apply()

    var forceUtf8: Boolean
        get() = sp.getBoolean("utf8", false)
        set(v) = sp.edit().putBoolean("utf8", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("screenOn", true)
        set(v) = sp.edit().putBoolean("screenOn", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    fun printerConfig() = PrinterConfig(
        paperDots = if (paper80) 576 else 384,
        defaultCodePage = codePage,
        forceUtf8 = forceUtf8,
    )
}
