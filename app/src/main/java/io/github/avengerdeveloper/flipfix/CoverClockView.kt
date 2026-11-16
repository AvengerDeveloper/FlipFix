package io.github.avengerdeveloper.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import java.util.Date

/**
 * Draws the cover clock exactly where Samsung's lock screen draws it, so unlocking looks seamless.
 * Positions are computed in cover-display coordinates and converted to this view's coordinates.
 */
class CoverClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var layout = CoverClockLayout.load(context)

    /** Offset of Samsung's clock from the template's, as measured on the lock screen (cover fractions). */
    private var shift = CoverFaceMatcher.activeShift(context)
    private val renderer = CoverClockRenderer(context)

    /**
     * True (default): pinned to the cover display, wherever the view sits. False: lays out in its own
     * bounds, for the edge-peek card that represents the whole cover while it slides and scales.
     */
    var screenAnchored = true

    private val location = IntArray(2)

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidate()
        }
    }

    /** The active cover preset was identified (or changed): pick up its clock. */
    private val presetObserver: () -> Unit = { post { reloadStyleAsync() } }

    /**
     * Re-read the active preset's template in the background (the user may have switched cover presets);
     * redraws only if something changed, so the unlock path is never slowed down.
     */
    fun reloadStyleAsync() {
        Thread {
            val fresh = CoverClockLayout.load(context)
            val freshShift = CoverFaceMatcher.activeShift(context)
            if (fresh != layout || freshShift != shift) post { layout = fresh; shift = freshShift; invalidate() }
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
        CoverFaceMatcher.observe(presetObserver)
    }

    /** An analog clock with a second hand redraws on every second while it's on screen. */
    private val secondTick = Runnable { invalidate() }

    private fun scheduleSecondTick() {
        removeCallbacks(secondTick)
        val l = layout
        if (l is CoverClockLayout.Analog && l.second != null) {
            postDelayed(secondTick, 1_000L - System.currentTimeMillis() % 1_000L + 5L)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(secondTick)
        CoverFaceMatcher.stopObserving(presetObserver)
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (screenAnchored) {
            // Full cover-display size: the layout is defined in display coordinates, not view coordinates.
            val windowBounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            getLocationOnScreen(location)
            val w = (windowBounds?.width() ?: width).toFloat()
            val h = (windowBounds?.height() ?: height).toFloat()
            renderer.draw(
                canvas, layout, Date(), w, h,
                location[0] - shift.first * w, location[1] - shift.second * h,
            )
        } else {
            // Drawn inside a moving/scaled card that represents the whole cover: lay out in the view's own
            // coordinates so the clock moves and scales with the card.
            val w = width.toFloat()
            val h = height.toFloat()
            renderer.draw(canvas, layout, Date(), w, h, -shift.first * w, -shift.second * h)
        }
        // Only a drawn frame schedules the next one: off screen, the ticking stops by itself.
        scheduleSecondTick()
    }
}
