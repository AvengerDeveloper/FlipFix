package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Draws a cover clock layout in cover-display coordinates. Used by the cover face (CoverClockView) and,
 * offscreen, by CoverFaceMatcher to compare what FlipFix would draw with Samsung's lock screen.
 */
class CoverClockRenderer(private val context: Context) {

    // Samsung's path-drawn digits (SamsungClockDigits), stroked as round lines.
    private val digitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            is CoverClockLayout.TwoLine -> drawTwoLine(canvas, layout, now, locale, screenW, screenH, offX, offY)
            is CoverClockLayout.Sideways -> drawSideways(canvas, layout, now, locale, screenW, screenH, offX, offY)
            is CoverClockLayout.Analog -> drawAnalog(canvas, layout, now, locale, screenW, screenH, offX, offY)
            CoverClockLayout.Unknown -> Unit
        }
    }

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dialRect = RectF()
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val secondHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val calendar = Calendar.getInstance()

    /**
     * Analog dial: the template's own numerals (or ticks), then the hands for [now] (hour and minute hands
     * move continuously, the second hand ticks), then the date line.
     */
    private fun drawAnalog(
        canvas: Canvas, l: CoverClockLayout.Analog, now: Date, locale: Locale,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        dialRect.set(
            l.dialLeftFrac * screenW - offX, l.dialTopFrac * screenH - offY,
            l.dialRightFrac * screenW - offX, l.dialBottomFrac * screenH - offY,
        )
        canvas.drawBitmap(l.dial, null, dialRect, dialPaint)

        calendar.timeZone = java.util.TimeZone.getDefault()
        calendar.time = now
        val seconds = calendar.get(Calendar.SECOND)
        val minutes = calendar.get(Calendar.MINUTE) + seconds / 60f
        val hours = calendar.get(Calendar.HOUR) + minutes / 60f
        handPaint.color = l.color or 0xFF000000.toInt()
        secondHandPaint.color = l.secondColor or 0xFF000000.toInt()
        pinPaint.color = l.pinColor or 0xFF000000.toInt()
        CoverClockAnalog.drawHands(
            canvas, l.hour, l.minute, l.second, l.pinRadiusFrac,
            l.centerXFrac * screenW - offX, l.centerYFrac * screenH - offY, screenH,
            hours * 30f, minutes * 6f, seconds * 6f, handPaint, secondHandPaint, pinPaint,
        )

        l.date?.let { d ->
            drawDateLine(
                canvas, now, locale, d.longMonth, d.align, d.anchorXFrac, d.textSizeFrac, d.baselineFrac, d.color,
                screenW, screenH, offX, offY,
            )
        }
    }

    /** A date line whose ink is anchored at [anchorXFrac] by [align] (as the template has it). */
    private fun drawDateLine(
        canvas: Canvas, now: Date, locale: Locale, longMonth: Boolean, align: CoverClockLayout.Align,
        anchorXFrac: Float, textSizeFrac: Float, baselineFrac: Float, color: Int,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val dateText = DateFormat.format(
            DateFormat.getBestDateTimePattern(locale, if (longMonth) "EEEdMMMM" else "EEEdMMM"), now,
        ).toString()
        lineDatePaint.textSize = textSizeFrac * screenH
        lineDatePaint.color = color or 0xFF000000.toInt()
        lineDatePaint.getTextBounds(dateText, 0, dateText.length, bounds)
        val anchor = anchorXFrac * screenW
        val x = when (align) {
            CoverClockLayout.Align.LEFT -> anchor - bounds.left
            CoverClockLayout.Align.CENTER -> anchor - (bounds.left + bounds.right) / 2f
            CoverClockLayout.Align.RIGHT -> anchor - bounds.right
        }
        canvas.drawText(dateText, x - offX, baselineFrac * screenH - offY, lineDatePaint)
    }

    private val twoLinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val sideDatePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = CoverClockStyle.dateTypeface() }
    }
    private val dateBounds = Rect()

    /**
     * The one-line clock on its side, reading upwards: drawn upright in the line's own frame (x up the
     * cover, y across it from the left), then turned a quarter left onto the cover.
     */
    private fun drawSideways(
        canvas: Canvas, l: CoverClockLayout.Sideways, now: Date, locale: Locale,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val tf = CoverClockFonts.typeface(context, l.timeFont, l.timeWeight) ?: return
        val timeText = DateFormat.format(timePattern(locale), now).toString()
        val dateText = DateFormat.format(
            DateFormat.getBestDateTimePattern(locale, if (l.dateLongMonth) "EEEdMMMM" else "EEEdMMM"), now,
        ).toString()
        twoLinePaint.typeface = tf
        twoLinePaint.textSize = l.timeTextSizeFrac * screenH
        twoLinePaint.color = l.color or 0xFF000000.toInt()
        twoLinePaint.getTextBounds(timeText, 0, timeText.length, bounds)
        sideDatePaint.textSize = l.dateTextSizeFrac * screenH
        sideDatePaint.textScaleX = l.dateScaleX
        sideDatePaint.color = l.dateColor or 0xFF000000.toInt()
        sideDatePaint.getTextBounds(dateText, 0, dateText.length, dateBounds)

        // Upright frame: as long as the cover is tall, as tall as the cover is wide.
        val anchor = l.anchorFrac * screenH
        val gap = l.gapFrac * screenH
        val timeX: Float
        val dateX: Float
        if (l.timeFirst) {
            timeX = anchor - bounds.left
            dateX = timeX + bounds.right + gap - dateBounds.left
        } else {
            timeX = anchor - bounds.right
            dateX = timeX + bounds.left - gap - dateBounds.right
        }
        canvas.save()
        // Upright (x, y) -> cover (y, screenH - x).
        canvas.translate(-offX, screenH - offY)
        canvas.rotate(-90f)
        canvas.drawText(timeText, timeX, l.timeBaselineFrac * screenW, twoLinePaint)
        canvas.drawText(dateText, dateX, l.dateBaselineFrac * screenW, sideDatePaint)
        canvas.restore()
    }

    /** Hours over minutes in the preset's AOD font, with the date line where the template has it. */
    private fun drawTwoLine(
        canvas: Canvas, l: CoverClockLayout.TwoLine, now: Date, locale: Locale,
        screenW: Float, screenH: Float, offX: Float, offY: Float,
    ) {
        val hours = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH" else "hh", now).toString()
        val minutes = DateFormat.format("mm", now).toString()
        val opaque = l.color or 0xFF000000.toInt()
        val p = l.paths
        if (p != null) {
            // Fixed slots: each line sits exactly where the template's sample does.
            drawPathDigits(
                canvas, "12", hours, p.hoursLeftFrac * screenW - offX, p.hoursBottomFrac * screenH - offY,
                p.heightFrac * screenH, opaque, p.topAlpha, p.thickness, p.digitSet,
            )
            drawPathDigits(
                canvas, "45", minutes, p.minutesLeftFrac * screenW - offX, p.minutesBottomFrac * screenH - offY,
                p.heightFrac * screenH, opaque, p.topAlpha, p.thickness, p.digitSet,
            )
        } else {
            val tf = CoverClockFonts.typeface(context, l.font, l.weight) ?: return
            twoLinePaint.typeface = tf
            twoLinePaint.textSize = l.textSizeFrac * screenH
            twoLinePaint.color = opaque
            val anchor = l.anchorXFrac * screenW
            val lines = if (l.oneLine) {
                listOf(DateFormat.format(timePattern(locale), now).toString() to l.hoursBaselineFrac)
            } else {
                listOf(hours to l.hoursBaselineFrac, minutes to l.minutesBaselineFrac)
            }
            for ((text, baseline) in lines) {
                val adv = twoLinePaint.measureText(text)
                val x = when (l.align) {
                    CoverClockLayout.Align.LEFT -> anchor
                    CoverClockLayout.Align.CENTER -> anchor - adv / 2
                    CoverClockLayout.Align.RIGHT -> anchor - adv
                }
                canvas.drawText(text, x - offX, baseline * screenH - offY, twoLinePaint)
            }
        }

        drawDateLine(
            canvas, now, locale, l.dateLongMonth, l.align, l.dateAnchorXFrac, l.dateTextSizeFrac, l.dateBaselineFrac,
            l.dateColor, screenW, screenH, offX, offY,
        )
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
        // "HH:mm" fills the five slots; a one-digit hour ("9:05") is right-aligned into them.
        drawPathDigits(
            canvas, CoverClockLayout.SAMPLE_TIME, timeText, l.digitsLeftFrac * screenW - offX,
            l.digitsBottomFrac * screenH - offY, l.digitHeightFrac * screenH, opaque, l.digitsTopAlpha, l.thickness, l.digitSet,
        )
    }

    /** Samsung's path digits, faded towards the top (opacity [topAlpha]) like Samsung's. */
    private fun drawPathDigits(
        canvas: Canvas, sample: String, text: String, left: Float, bottom: Float, height: Float,
        opaque: Int, topAlpha: Float, thickness: Float, set: SamsungClockDigits.Set,
    ) {
        val digits = SamsungClockDigits.load(context, set) ?: return
        val faded = (opaque and 0x00FFFFFF) or ((topAlpha * 255).toInt() shl 24)
        val fade = LinearGradient(0f, bottom - height, 0f, bottom, faded, opaque, Shader.TileMode.CLAMP)
        digitStrokePaint.shader = fade
        dotPaint.shader = fade
        digits.draw(canvas, sample, text, left, bottom, height, digitStrokePaint, dotPaint, thickness)
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
