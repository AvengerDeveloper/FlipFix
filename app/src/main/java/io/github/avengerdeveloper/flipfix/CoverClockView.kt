package io.github.avengerdeveloper.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import java.util.Date
import java.util.Locale

/**
 * Draws the cover clock line exactly where Samsung's lock screen draws it, so unlocking looks seamless.
 * Positions are computed in cover-display coordinates and converted to this view's coordinates.
 */
class CoverClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var layout = CoverClockLayout.load()

    // Stacked style (Z Flip8 default): Samsung's path-drawn digits under a date line.
    private val samsungDigits by lazy { SamsungClockDigits.load(context) }
    private val digitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPath = android.graphics.Path()
    private val digitMatrix = android.graphics.Matrix()
    private val lineDatePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = CoverClockLayout.stackedDateTypeface()
            letterSpacing = CoverClockLayout.DATE_LETTER_SPACING
        }
    }

    /**
     * True (default): pinned to the cover display, wherever the view sits. False: lays out in its own
     * bounds, for the edge-peek card that represents the whole cover while it slides and scales.
     */
    var screenAnchored = true

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = CoverClockStyle.timeTypeface(context)
        letterSpacing = CoverClockStyle.TIME_LETTER_SPACING
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = CoverClockStyle.dateTypeface()
    }
    private val bounds = Rect()
    private val location = IntArray(2)

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidate()
        }
    }

    /**
     * Re-read the template in the background (the user may have changed their cover wallpaper or clock);
     * redraws only if something changed, so the unlock path is never slowed down.
     */
    fun reloadStyleAsync() {
        Thread {
            val fresh = CoverClockLayout.load()
            if (fresh != layout) post { layout = fresh; invalidate() }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        // System broadcasts: EXPORTED so they are delivered reliably.
        context.registerReceiver(tick, filter, Context.RECEIVER_EXPORTED)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Full cover-display size: the layout is defined in display coordinates, not view coordinates.
        val screenW: Float
        val screenH: Float
        val offX: Float
        val offY: Float
        if (screenAnchored) {
            val windowBounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            screenW = (windowBounds?.width() ?: width).toFloat()
            screenH = (windowBounds?.height() ?: height).toFloat()
            getLocationOnScreen(location)
            offX = location[0].toFloat()
            offY = location[1].toFloat()
        } else {
            // Drawn inside a moving/scaled card that represents the whole cover: lay out in the view's own
            // coordinates so the clock moves and scales with the card.
            screenW = width.toFloat()
            screenH = height.toFloat()
            offX = 0f
            offY = 0f
        }

        val now = Date()
        val locale = Locale.getDefault()
        when (val l = layout) {
            is CoverClockLayout.Horizontal -> drawHorizontal(canvas, l.style, now, locale, screenW, screenH, offX, offY)
            is CoverClockLayout.Stacked -> drawStacked(canvas, l, now, locale, screenW, screenH, offX, offY)
            // Safety net: an unrecognised Samsung clock style gets no clock rather than a wrong one.
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
        datePaint.getTextBounds(dateText, 0, dateText.length, this.bounds)
        val dateX = right - this.bounds.right
        val dateInkLeft = dateX + this.bounds.left

        // Time: its ink ends one gap before the date's ink starts.
        timePaint.getTextBounds(timeText, 0, timeText.length, this.bounds)
        val timeX = dateInkLeft - digitH * CoverClockStyle.GAP_PER_DIGIT_HEIGHT - this.bounds.right

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
