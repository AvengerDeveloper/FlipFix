package io.github.avengerdeveloper.flipfix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/**
 * Dev check, in debug builds only: reads every cover preset's template as an analog clock and draws
 * FlipFix's clock for it at the given times, without installing anything or waking the phone:
 *
 *   adb push app-debug.apk /data/local/tmp/flipfix-check.apk
 *   adb shell CLASSPATH=/data/local/tmp/flipfix-check.apk app_process / \
 *       io.github.avengerdeveloper.flipfix.CoverClockCheck 17:15:57 17:16:01
 *
 * Writes /data/local/tmp/flipfix-check/<preset>-<HHmmss>.png (cover-sized, transparent).
 */
object CoverClockCheck {

    private const val COVER_W = 948
    private const val COVER_H = 1048

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File("/data/local/tmp/flipfix-check").apply { mkdirs() }
        for (file in CoverClockLayout.templates()) {
            val id = file.parentFile?.name ?: continue
            val bmp = BitmapFactory.decodeFile(file.path) ?: continue
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val t = CoverClockLayout.Template(px, bmp.width, bmp.height)
            val layout = CoverClockAnalog.parse(t, dateFont = null)
            println("$id: $layout")
            if (layout !is CoverClockLayout.Analog) { bmp.recycle(); continue }
            println("$id: dial and hands at 12:45:00 vs template, overlap %.3f".format(templateOverlap(bmp, layout)))
            bmp.recycle()
            for (time in args) {
                val (h, m, s) = time.split(':').map { it.toInt() }
                val clock = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
                drawDialAndHands(Canvas(clock), layout, h, m, s)
                File(out, "$id-${time.replace(":", "")}.png").outputStream().use {
                    clock.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                clock.recycle()
            }
        }
    }

    /**
     * Overlap (IoU of solid ink) of FlipFix's drawing at the template's time with the whole template scaled
     * to the cover, from the dial's top down (the status icons and date above it aren't drawn here).
     */
    private fun templateOverlap(template: Bitmap, l: CoverClockLayout.Analog): Float {
        val scaled = Bitmap.createScaledBitmap(template, COVER_W, COVER_H, true)
        val drawn = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
        drawDialAndHands(Canvas(drawn), l, 12, 45, 0)
        val a = IntArray(COVER_W * COVER_H).also { scaled.getPixels(it, 0, COVER_W, 0, 0, COVER_W, COVER_H) }
        val b = IntArray(COVER_W * COVER_H).also { drawn.getPixels(it, 0, COVER_W, 0, 0, COVER_W, COVER_H) }
        scaled.recycle(); drawn.recycle()
        var both = 0; var either = 0
        for (i in (l.dialTopFrac * COVER_H).toInt() * COVER_W until a.size) {
            val ta = a[i] ushr 24 > 127
            val fa = b[i] ushr 24 > 127
            if (ta && fa) both++
            if (ta || fa) either++
        }
        return if (either == 0) 0f else both.toFloat() / either
    }

    /**
     * What CoverClockRenderer draws for an analog clock, minus the date: a bare app_process can't read the
     * system font configuration, so no text can be drawn here.
     */
    private fun drawDialAndHands(canvas: Canvas, l: CoverClockLayout.Analog, h: Int, m: Int, s: Int) {
        val w = COVER_W.toFloat(); val hh = COVER_H.toFloat()
        canvas.drawBitmap(
            l.dial, null,
            RectF(l.dialLeftFrac * w, l.dialTopFrac * hh, l.dialRightFrac * w, l.dialBottomFrac * hh),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = c or 0xFF000000.toInt()
        }
        val minutes = m + s / 60f
        CoverClockAnalog.drawHands(
            canvas, l.hour, l.minute, l.second, l.pinRadiusFrac, l.centerXFrac * w, l.centerYFrac * hh, hh,
            (h % 12 + minutes / 60f) * 30f, minutes * 6f, s * 6f,
            stroke(l.color), stroke(l.secondColor), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.pinColor or 0xFF000000.toInt() },
        )
    }
}
