package io.github.avengerdeveloper.flipfix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Reads an analog cover clock from its template. Samsung renders templates at 12:45:00, so the minute hand
 * points at 9 (the template's longest horizontal bar), the hour hand a little past 12 and the second hand,
 * if the style has one, straight up. From those FlipFix finds the centre, measures each hand (length,
 * width, the second hand's tail, the centre pin) and keeps the rest of the dial (numerals or ticks) as the
 * template has it, with the sample hands taken out.
 *
 * Safety net: FlipFix draws its hands at 12:45:00 and compares them with the template's. Styles whose hands
 * aren't plain round-ended bars (shaped or illustrated hands) don't match and stay undrawn.
 */
internal object CoverClockAnalog {

    /** Minimum overlap (intersection over union) of FlipFix's hands at 12:45:00 with the template's. */
    private const val MIN_HANDS_OVERLAP = 0.85f

    private const val HOUR_ANGLE = 22.5f // 12:45
    private const val MINUTE_ANGLE = 270f
    private const val SECOND_ANGLE = 0f

    /** [dateFont] null skips the date line (the debug check: a bare app_process has no system fonts). */
    fun parse(t: CoverClockLayout.Template, dateFont: CoverClockLayout.DateFont?): CoverClockLayout {
        val from = (t.h * 0.10f).toInt()

        // The minute hand: the longest horizontal run of ink, a few rows thick.
        val longest = IntArray(t.h)
        var barLen = 0; var barRow = -1; var barLeft = 0; var barRight = 0
        for (y in from until t.h) {
            var start = -1
            for (x in 0..t.w) {
                val solid = x < t.w && t.solid(x, y)
                if (solid && start < 0) start = x
                if (!solid && start >= 0) {
                    val len = x - start
                    if (len > longest[y]) longest[y] = len
                    if (len > barLen) { barLen = len; barRow = y; barLeft = start; barRight = x - 1 }
                    start = -1
                }
            }
        }
        if (barRow < 0 || barLen < t.w * 0.08f) return CoverClockLayout.Unknown
        var top = barRow
        while (top - 1 >= from && longest[top - 1] >= barLen * 0.9f) top--
        var bottom = barRow
        while (bottom + 1 < t.h && longest[bottom + 1] >= barLen * 0.9f) bottom++

        // Its exact middle row and thickness, from the opacity across it.
        val probeX = barLeft + barLen * 0.3f
        var sum = 0f; var weighted = 0f
        for (y in (top - 6).coerceAtLeast(0)..(bottom + 6).coerceAtMost(t.h - 1)) {
            val a = t.alphaAt(probeX, y.toFloat())
            sum += a; weighted += a * y
        }
        if (sum < 2f || sum > barLen * 0.2f) return CoverClockLayout.Unknown
        val thick = sum
        val cy = weighted / sum

        // The centre's x: where the hour hand (22.5° past 12) comes from, row by row above the centre...
        val slope = tan(Math.toRadians(HOUR_ANGLE.toDouble())).toFloat()
        val fromHour = mutableListOf<Float>()
        for (y in (cy - 0.45f * barLen).toInt() until (cy - 0.2f * barLen).toInt()) {
            if (y < 0) continue
            var bestW = 0; var bestMid = 0f
            var start = -1
            for (x in 0..t.w) {
                val solid = x < t.w && t.solid(x, y)
                if (solid && start < 0) start = x
                if (!solid && start >= 0) {
                    val mid = (start + x - 1) / 2f
                    if (x - start > bestW && mid in (barRight - 0.15f * barLen)..(barRight + 0.3f * barLen)) {
                        bestW = x - start; bestMid = mid
                    }
                    start = -1
                }
            }
            if (bestW < 0.5f * thick) continue
            var s = 0f; var c = 0f
            for (x in (bestMid - bestW).toInt()..(bestMid + bestW).toInt()) {
                val a = t.alphaAt(x.toFloat(), y.toFloat()); s += a; c += a * x
            }
            if (s > 0f) fromHour += c / s - (cy - y) * slope
        }
        if (fromHour.size < 0.1f * barLen) return CoverClockLayout.Unknown
        var cx = fromHour.median()
        // ...or, more exactly, the thin second hand straight above it.
        val fromSecond = mutableListOf<Float>()
        for (y in (cy - 0.6f * barLen).toInt() until (cy - 0.4f * barLen).toInt()) {
            if (y < 0) continue
            var s = 0f; var c = 0f
            for (x in cx.toInt() - 6..cx.toInt() + 6) {
                val a = t.alphaAt(x.toFloat(), y.toFloat()); s += a; c += a * x
            }
            if (s >= 1f && s <= 0.5f * thick) fromSecond += c / s
        }
        if (fromSecond.size >= 0.1f * barLen) cx = fromSecond.median()

        // Hands: how far ink reaches along each one's direction, and how wide it is.
        val maxReach = (barLen * 1.3f).toInt()
        val half = maxOf(8f, thick * 0.9f)
        fun width(angle: Float, r: Float, half: Float): Float {
            val rad = Math.toRadians(angle.toDouble())
            val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
            var w = 0f
            var k = -half
            while (k <= half) { w += t.alphaAt(cx + dx * r - dy * k, cy + dy * r + dx * k) * 0.5f; k += 0.5f }
            return w
        }
        fun reach(angle: Float, half: Float): Int = (0..maxReach).firstOrNull { width(angle, it.toFloat(), half) < 0.5f } ?: maxReach
        fun medianWidth(angle: Float, half: Float, r0: Int, r1: Int): Float =
            (r0..maxOf(r0, r1)).map { width(angle, it.toFloat(), half) }.median()
        fun measure(angle: Float, half: Float, from: Float): Pair<Int, Float> {
            val end = reach(angle, half)
            return end to medianWidth(angle, half, (end * from).toInt(), end - 6)
        }
        val (hourLen, hourW) = measure(HOUR_ANGLE, half, 0.3f)
        val (minuteLen, minuteW) = measure(MINUTE_ANGLE, half, 0.3f)
        if (hourLen >= minuteLen || hourW < 1.5f || minuteW < 1.5f) return CoverClockLayout.Unknown

        // The centre pin: how far solid ink reaches from the centre where no hand points.
        fun rayEnd(angle: Float): Float {
            val rad = Math.toRadians(angle.toDouble())
            val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
            var r = 0f
            while (r < maxReach && t.alphaAt(cx + dx * r, cy + dy * r) >= 0.5f) r += 0.25f
            return r
        }
        val pinR = listOf(90f, 120f, 135f, 150f).map(::rayEnd).median()
            .let { if (it < maxOf(hourW, minuteW) / 2f + 1f) 0f else it }

        val (secondLen, secondW) = measure(SECOND_ANGLE, 8f, 0.35f)
        val second = if (secondLen >= 0.3f * minuteLen && secondW < minOf(hourW, minuteW)) {
            val tailLen = reach(SECOND_ANGLE + 180f, 8f)
            val tailW = if (tailLen > pinR + 2f) medianWidth(SECOND_ANGLE + 180f, 8f, pinR.toInt() + 1, tailLen - 4) else 0f
            Triple(secondLen, secondW, if (tailW > 0f) tailLen to tailW else null)
        } else null

        val unit = t.h.toFloat()
        val hour = CoverClockLayout.Hand(hourLen / unit, hourW / unit)
        val minute = CoverClockLayout.Hand(minuteLen / unit, minuteW / unit)
        val secondHand = second?.let { (len, w, tail) ->
            CoverClockLayout.Hand(len / unit, w / unit, (tail?.first ?: 0) / unit, (tail?.second ?: 0f) / unit)
        }

        // The dial: the band of ink around the centre, and the bands beyond it that have ink mirrored across
        // the centre (rows of numerals or ticks can have wide gaps between them, but sit symmetrically; the
        // date line doesn't, so it stays apart).
        val bands = t.bands(from = from, mergeGap = (t.h * 0.006f).toInt())
        val centreIndex = bands.indexOfFirst { cy.toInt() in it.top..it.bottom }
        if (centreIndex < 0) return CoverClockLayout.Unknown
        fun mirrored(b: CoverClockLayout.Band): Boolean {
            val y0 = (2 * cy - b.bottom).roundToInt()
            val y1 = (2 * cy - b.top).roundToInt()
            return y0 >= 0 && y1 < t.h && (y0..y1).any { y -> (0 until t.w).any { t.ink(it, y) } }
        }
        var first = centreIndex
        while (first > 0 && mirrored(bands[first - 1])) first--
        var last = centreIndex
        while (last < bands.lastIndex && mirrored(bands[last + 1])) last++
        val dialBand = CoverClockLayout.Band(bands[first].top, bands[last].bottom)
        val rect = t.inkRect(dialBand) ?: return CoverClockLayout.Unknown

        // Draw FlipFix's hands at the template's time: compare (in a corridor around them), and take the
        // template's hands out of the dial (slightly wider, for their soft edges).
        val ccx = cx + 0.5f - rect.left // pixel centres -> canvas coordinates, in the dial's frame
        val ccy = cy + 0.5f - rect.top
        fun mask(grow: Float): Pair<ByteArray, Int> {
            val bmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ALPHA_8)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            drawHands(
                Canvas(bmp), hour, minute, secondHand, pinR / unit, ccx, ccy, unit,
                HOUR_ANGLE, MINUTE_ANGLE, SECOND_ANGLE, stroke, stroke, fill, grow,
            )
            val bytes = ByteArray(bmp.rowBytes * bmp.height).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
            val stride = bmp.rowBytes
            bmp.recycle()
            return bytes to stride
        }
        val (drawn, stride) = mask(0f)
        val (corridor, _) = mask(12f)
        val (eraser, _) = mask(4f)
        var both = 0; var either = 0
        val dial = IntArray(rect.width() * rect.height())
        for (y in 0 until rect.height()) for (x in 0 until rect.width()) {
            val i = y * stride + x
            val tx = rect.left + x; val ty = rect.top + y
            if (corridor[i].toInt() != 0) {
                val a = (drawn[i].toInt() and 0xFF) > 127
                val b = t.solid(tx, ty)
                if (a && b) both++
                if (a || b) either++
            }
            dial[y * rect.width() + x] = if (eraser[i].toInt() != 0 || t.inCameraCorner(tx, ty)) 0 else t.pixel(tx, ty)
        }
        val overlap = if (either == 0) 0f else both.toFloat() / either
        Log.d(
            CoverState.TAG,
            "cover clock analog: centre %.1f,%.1f hour %d/%.1f minute %d/%.1f second %s pin %.1f; hands overlap %.2f"
                .format(cx, cy, hourLen, hourW, minuteLen, minuteW, second, pinR, overlap),
        )
        if (overlap < MIN_HANDS_OVERLAP) return CoverClockLayout.Unknown

        // Colours: the hands', the second hand's (often an accent) and the pin's.
        val color = handColor(t, cx, cy, MINUTE_ANGLE, minuteLen) ?: Color.WHITE
        val secondColor = second?.let { handColor(t, cx, cy, SECOND_ANGLE, it.first, strongest = true) } ?: color
        val pinColor = t.pixel(cx.roundToInt(), cy.roundToInt()).takeIf { pinR > 0f && Color.alpha(it) > 240 }
            ?.let { it or 0xFF000000.toInt() } ?: color

        // The date: the thin line just above (or below) the dial.
        val date = (bands.getOrNull(first - 1) ?: bands.getOrNull(last + 1))?.takeIf { dateFont != null }
            ?.takeIf { it.height < dialBand.height * 0.25f }
            ?.let { t.inkRect(it) }
            ?.let { dateRect ->
                val align = CoverClockLayout.alignOf(dateRect.exactCenterX(), t.w)
                CoverClockLayout.dateFields(t, dateRect, align, color, dateFont ?: return@let null)?.let { d ->
                    CoverClockLayout.DateLine(d.long, align, d.anchor / t.w, d.size / t.h, d.baseline / t.h, d.color)
                }
            }

        return CoverClockLayout.Analog(
            dial = Bitmap.createBitmap(dial, rect.width(), rect.height(), Bitmap.Config.ARGB_8888),
            dialLeftFrac = rect.left.toFloat() / t.w,
            dialTopFrac = rect.top.toFloat() / t.h,
            dialRightFrac = rect.right.toFloat() / t.w,
            dialBottomFrac = rect.bottom.toFloat() / t.h,
            centerXFrac = (cx + 0.5f) / t.w,
            centerYFrac = (cy + 0.5f) / t.h,
            hour = hour,
            minute = minute,
            second = secondHand,
            pinRadiusFrac = pinR / unit,
            color = color,
            secondColor = secondColor,
            pinColor = pinColor,
            date = date,
        )
    }

    /** Mean colour of solid ink along a hand, or its most opaque pixel's ([strongest]: thin hands). */
    private fun handColor(t: CoverClockLayout.Template, cx: Float, cy: Float, angle: Float, len: Int, strongest: Boolean = false): Int? {
        val rad = Math.toRadians(angle.toDouble())
        val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var best = 0; var bestA = -1
        for (step in (len * 0.4f).toInt()..(len * 0.8f).toInt()) for (k in -2..2) {
            val x = (cx + dx * step - dy * k).roundToInt()
            val y = (cy + dy * step + dx * k).roundToInt()
            if (x !in 0 until t.w || y !in 0 until t.h) continue
            val c = t.pixel(x, y)
            val a = Color.alpha(c)
            if (a > bestA) { bestA = a; best = c }
            if (a > 240) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
        }
        if (strongest) return if (bestA > 60) best or 0xFF000000.toInt() else null
        return if (n == 0L) null else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * Hands at [hourDeg], [minuteDeg] and [secondDeg] (clockwise from 12) around ([cx], [cy]): round-ended
     * bars from the centre, the second hand with its tail, then the pin. Sizes are fractions of [scale];
     * [grow] widens everything (px), for masks.
     */
    fun drawHands(
        canvas: Canvas,
        hour: CoverClockLayout.Hand, minute: CoverClockLayout.Hand, second: CoverClockLayout.Hand?,
        pinRadiusFrac: Float, cx: Float, cy: Float, scale: Float,
        hourDeg: Float, minuteDeg: Float, secondDeg: Float,
        hand: Paint, secondPaint: Paint, pin: Paint, grow: Float = 0f,
    ) {
        fun bar(deg: Float, length: Float, width: Float, paint: Paint) {
            if (length <= 0f || width <= 0f) return
            val rad = Math.toRadians(deg.toDouble())
            val reach = (length - width / 2f).coerceAtLeast(0f)
            paint.strokeWidth = width
            canvas.drawLine(cx, cy, cx + sin(rad).toFloat() * reach, cy - cos(rad).toFloat() * reach, paint)
        }
        bar(hourDeg, hour.lengthFrac * scale + grow / 2f, hour.widthFrac * scale + grow, hand)
        bar(minuteDeg, minute.lengthFrac * scale + grow / 2f, minute.widthFrac * scale + grow, hand)
        if (second != null) {
            bar(secondDeg, second.lengthFrac * scale + grow / 2f, second.widthFrac * scale + grow, secondPaint)
            bar(secondDeg + 180f, second.tailFrac * scale + grow / 2f, second.tailWidthFrac * scale + grow, secondPaint)
        }
        if (pinRadiusFrac > 0f) canvas.drawCircle(cx, cy, pinRadiusFrac * scale + grow / 2f, pin)
    }

    private fun List<Float>.median(): Float = sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}
