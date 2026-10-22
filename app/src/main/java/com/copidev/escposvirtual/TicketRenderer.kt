package com.copidev.escposvirtual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.copidev.escposvirtual.core.ImageItem
import com.copidev.escposvirtual.core.NoteItem
import com.copidev.escposvirtual.core.TextItem
import com.copidev.escposvirtual.core.Ticket

/** Dibuja un [Ticket] en un Bitmap a escala 1:1 (un píxel = un punto de la impresora). */
object TicketRenderer {
    const val MARGIN = 16
    private const val CUT_SPACE = 28
    private const val RED = 0xFFD32F2F.toInt()

    fun render(t: Ticket): Bitmap {
        val w = t.paperDots + MARGIN * 2
        val h = t.height + MARGIN * 2 + (if (t.cut > 0 || t.truncated) CUT_SPACE else 0)
        val bmp = Bitmap.createBitmap(w, h.coerceAtLeast(MARGIN * 2 + 10), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 100f
        }
        val fm = text.fontMetrics
        val glyphH = fm.descent - fm.ascent
        val fill = Paint()

        for (item in t.items) {
            when (item) {
                is TextItem -> drawText(c, item, text, glyphH, -fm.ascent)
                is ImageItem -> drawImage(c, item)
                is NoteItem -> drawNote(c, item)
            }
        }

        if (t.cut > 0 || t.truncated) {
            val y = (h - CUT_SPACE / 2).toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF9E9E9E.toInt()
                strokeWidth = 2f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            }
            c.drawLine(0f, y, w.toFloat(), y, p)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF757575.toInt(); textSize = 16f }
            val s = when {
                t.truncated -> "✂ ticket recortado (demasiado largo)"
                t.cut == 2 -> "✂ corte total"
                else -> "✂ corte parcial"
            }
            val tw = label.measureText(s)
            fill.color = Color.WHITE
            c.drawRect(w - tw - 24, y - 12, w - 8f, y + 12, fill)
            c.drawText(s, w - tw - 16, y + 6, label)
        }
        return bmp
    }

    private fun drawText(c: Canvas, it: TextItem, p: Paint, glyphH: Float, ascent: Float) {
        val step = it.cellW + it.spacing
        p.isFakeBoldText = it.bold
        for ((i, ch) in it.text.withIndex()) {
            val x0 = (MARGIN + it.x + i * step).toFloat()
            val y0 = (MARGIN + it.y).toFloat()
            if (it.reverse) {
                p.color = if (it.red) RED else Color.BLACK
                c.drawRect(x0, y0, x0 + step, y0 + it.cellH, p)
                p.color = Color.WHITE
            } else {
                p.color = if (it.red) RED else Color.BLACK
            }
            if (ch != ' ') {
                val s = ch.toString()
                val adv = p.measureText(s).coerceAtLeast(1f)
                c.save()
                c.translate(x0, y0)
                if (it.upsideDown) c.rotate(180f, it.cellW / 2f, it.cellH / 2f)
                c.scale(it.cellW / adv, it.cellH / glyphH)
                c.drawText(s, 0f, ascent, p)
                c.restore()
            }
            if (it.underline > 0) {
                c.drawRect(x0, y0 + it.cellH - it.underline, x0 + step, y0 + it.cellH.toFloat(), p)
            }
        }
        p.isFakeBoldText = false
    }

    private fun drawImage(c: Canvas, it: ImageItem) {
        if (it.w <= 0 || it.h <= 0) return
        val ink = if (it.red) RED else Color.BLACK
        val colors = IntArray(it.w * it.h) { i -> if (it.pixels[i].toInt() == 1) ink else Color.WHITE }
        val b = Bitmap.createBitmap(colors, it.w, it.h, Bitmap.Config.RGB_565)
        c.drawBitmap(b, (MARGIN + it.x).toFloat(), (MARGIN + it.y).toFloat(), null)
        b.recycle()
    }

    private fun drawNote(c: Canvas, it: NoteItem) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8D6E00.toInt()
            textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        }
        val layout = StaticLayout.Builder.obtain(it.text, 0, it.text.length, tp, it.w.coerceAtLeast(50))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .build()
        c.save()
        c.translate((MARGIN + it.x).toFloat(), (MARGIN + it.y).toFloat())
        layout.draw(c)
        c.restore()
    }
}
