package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws a cover clock layout in cover-display coordinates. Used by the cover face (CoverClockView) and,
 * offscreen, by CoverFaceMatcher to compare what FlipFix would draw with Samsung's lock screen.
 */
class CoverClockRenderer(private val context: Context) {

    // Stacked style (Z Flip8 default): Samsung's path-drawn digits under a date line.
    private val samsungDigits by lazy { SamsungClockDigits.load(context) }
    private val digitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPath = Path()
    private val digitMatrix = Matrix()
    private val lineDatePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = CoverClockLayout.stackedDateTypeface()
            letterSpacing = CoverClockLayout.DATE_LETTER_SPACING
        }
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = CoverClockStyle.timeTypeface(context)
        letterSpacing = CoverClockStyle.TIME_LETTER_SPACING
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = CoverClockStyle.dateTypeface()
    }
    private val bounds = Rect()

    /**
     * Draws [layout] for the time [now] on a cover of [screenW] x [screenH]; ([offX], [offY]) is where the
     * canvas origin sits on the cover. [CoverClockLayout.Unknown] draws nothing (the safety net).
     */
    fun draw(
        canvas: Canvas, layout: CoverClockLayout, now: Date,
        screenW: Float, screenH: Float, offX: Float = 0f, offY: Float = 0f,
    ) {
        val locale = Locale.getDefault()
        when (layout) {
            is CoverClockLayout.Horizontal -> drawHorizontal(canvas, layout.style, now, locale, screenW, screenH, offX, offY)
            is CoverClockLayout.Stacked -> drawStacked(canvas, layout, now, locale, screenW, screenH, offX, offY)
            CoverClockLayout.Unknown -> Unit
        }
    }

    /**
     * Stacked style: big rounded digits centred where the template puts them, faded towards the top, with
     * the date above at the spot it has on the lock screen (Samsung centres "date + weather" as one line;
     * FlipFix can't read Samsung's weather, so it leaves the weather's space empty to keep the date put).
     */
    private fun drawStacked(
        canvas: Canvas, l: CoverClockLayout.Stacked, now: Date, locale: Locale,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val timeText = DateFormat.format(timePattern(locale), now).toString()
        val dateText = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), now).toString()

        val opaque = l.color or 0xFF000000.toInt()
        drawStackedDigits(canvas, l, timeText, opaque, screenW, screenH, offX, offY)

        // Date line: as tall as the template's date (sized by letters with ascenders); start where
        // "date + weather" would start.
        val dateH = l.dateHeightFrac * screenH
        val asc = CoverClockLayout.ASCENDERS
        lineDatePaint.textSize = 100f
        lineDatePaint.getTextBounds(asc, 0, asc.length, bounds)
        lineDatePaint.textSize = 100f * dateH / bounds.height().coerceAtLeast(1)
        lineDatePaint.color = opaque
        lineDatePaint.getTextBounds(dateText, 0, dateText.length, bounds)
        val lineWidth = bounds.width() + dateH * CoverClockLayout.WEATHER_SLOT_PER_DATE_HEIGHT
        val dateX = l.lineCenterXFrac * screenW - lineWidth / 2f - bounds.left
        canvas.drawText(dateText, dateX - offX, l.dateBaselineFrac * screenH - offY, lineDatePaint)
    }

    /**
     * Samsung's stacked digits, drawn from Samsung's own digit paths (SamsungClockDigits) as thick round
     * lines. Scale, left edge and bottom come from the template's sample "12:45"; digits sit in fixed
     * slots, so every time lines up the way Samsung's does. No paths -> no digits (safety net).
     */
    private fun drawStackedDigits(
        canvas: Canvas, l: CoverClockLayout.Stacked, timeText: String, opaque: Int,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val digits = samsungDigits ?: return
        val sample = digits.bounds(CoverClockLayout.SAMPLE_TIME)
        val s = l.digitHeightFrac * screenH / (sample.height() + SamsungClockDigits.STROKE)
        val w = SamsungClockDigits.STROKE * s
        val bottom = l.digitsBottomFrac * screenH
        val centerY = bottom - sample.bottom * s - w / 2
        val top = centerY + sample.top * s - w / 2
        // The sample starts with "1": its ink left edge fixes the first slot.
        val firstSlot = l.digitsLeftFrac * screenW - digits.bounds("1").left * s + w / 2
        val a = SamsungClockDigits.ADVANCE * s
        val g = SamsungClockDigits.COLON_GAP * s
        val slots = floatArrayOf(firstSlot, firstSlot + a, firstSlot + a + g, firstSlot + a + 2 * g, firstSlot + 2 * a + 2 * g)

        val faded = (opaque and 0x00FFFFFF) or ((l.digitsTopAlpha * 255).toInt() shl 24)
        val fade = LinearGradient(0f, top - offY, 0f, bottom - offY, faded, opaque, Shader.TileMode.CLAMP)
        digitStrokePaint.strokeWidth = w
        digitStrokePaint.shader = fade
        dotPaint.shader = fade

        // "HH:mm" fills the five slots; a one-digit hour ("9:05") is right-aligned into them.
        val chars = timeText.filter { it.isDigit() || it == ':' }
        val startSlot = (slots.size - chars.length).coerceAtLeast(0)
        chars.forEachIndexed { i, c ->
            val x = slots.getOrNull(startSlot + i) ?: return@forEachIndexed
            if (c == ':') {
                val r = SamsungClockDigits.DOT_DIAMETER * s / 2
                canvas.drawCircle(x - offX, centerY - SamsungClockDigits.DOT_OFFSET * s - offY, r, dotPaint)
                canvas.drawCircle(x - offX, centerY + SamsungClockDigits.DOT_OFFSET * s - offY, r, dotPaint)
            } else {
                digitMatrix.setScale(s, s)
                digitMatrix.postTranslate(x - offX, centerY - offY)
                digitPath.set(digits.path(c - '0'))
                digitPath.transform(digitMatrix)
                canvas.drawPath(digitPath, digitStrokePaint)
            }
        }
    }

    private fun drawHorizontal(
        canvas: Canvas, style: CoverClockStyle, now: Date, locale: Locale,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val digitH = style.digitHeightFrac * screenH
        timePaint.textSize = digitH * CoverClockStyle.TIME_SIZE_PER_DIGIT_HEIGHT
        datePaint.textSize = timePaint.textSize * CoverClockStyle.DATE_SIZE_PER_TIME_SIZE
        timePaint.color = style.color
        datePaint.color = style.color

        val timeText = DateFormat.format(timePattern(locale), now).toString()
        val dateText = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMMM"), now).toString()

        val right = (style.rightFrac + CoverClockStyle.RIGHT_NUDGE_FRAC) * screenW
        val timeBaseline = (style.timeBaselineFrac + CoverClockStyle.DOWN_NUDGE_FRAC) * screenH
        val dateBaseline = timeBaseline - digitH * CoverClockStyle.DATE_RAISE_PER_DIGIT_HEIGHT

        // Date: its ink ends exactly at the right edge.
        datePaint.getTextBounds(dateText, 0, dateText.length, bounds)
        val dateX = right - bounds.right
        val dateInkLeft = dateX + bounds.left

        // Time: its ink ends one gap before the date's ink starts.
        timePaint.getTextBounds(timeText, 0, timeText.length, bounds)
        val timeX = dateInkLeft - digitH * CoverClockStyle.GAP_PER_DIGIT_HEIGHT - bounds.right

        canvas.drawText(timeText, timeX - offX, timeBaseline - offY, timePaint)
        canvas.drawText(dateText, dateX - offX, dateBaseline - offY, datePaint)
    }

    /** Samsung's cover clock shows hours and minutes only (no AM/PM), following the 12/24-hour setting. */
    private fun timePattern(locale: Locale): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return DateFormat.getBestDateTimePattern(locale, skeleton)
            .replace("a", "").replace(" ", " ").trim()
    }
}
