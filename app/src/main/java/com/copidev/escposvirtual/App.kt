package com.copidev.escposvirtual

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda cualquier cierre inesperado para mostrarlo en el siguiente arranque. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = "Versión ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nHilo: ${thread.name}\n\n"
                crashFile(this).writeText(info + sw.toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(ctx: Context) = File(ctx.filesDir, "ultimo_error.txt")
    }
}
