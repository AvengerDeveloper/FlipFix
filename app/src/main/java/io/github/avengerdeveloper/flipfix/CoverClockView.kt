package io.github.avengerdeveloper.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

    private var style = CoverClockStyle.load()

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
            val fresh = CoverClockStyle.load()
            if (fresh != style) post { style = fresh; invalidate() }
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

        val digitH = style.digitHeightFrac * screenH
        timePaint.textSize = digitH * CoverClockStyle.TIME_SIZE_PER_DIGIT_HEIGHT
        datePaint.textSize = timePaint.textSize * CoverClockStyle.DATE_SIZE_PER_TIME_SIZE
        timePaint.color = style.color
        datePaint.color = style.color

        val now = Date()
        val locale = Locale.getDefault()
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
