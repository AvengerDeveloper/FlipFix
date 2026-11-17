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

    /** Offset and size of Samsung's clock against the template's, as measured on the lock screen. */
    private var placement = CoverFaceMatcher.activePlacement(context)
    private val renderer = CoverClockRenderer(context)

    /** Blurred stand-ins for the widgets Samsung's lock screen shows under this clock (see CoverWidgets). */
    private var widgets = CoverWidgets.active(context)

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
            val freshPlacement = CoverFaceMatcher.activePlacement(context)
            val freshWidgets = CoverWidgets.active(context)
            if (fresh != layout || freshPlacement != placement || freshWidgets !== widgets) post {
                layout = fresh; placement = freshPlacement; widgets = freshWidgets; invalidate()
                if (windowVisibility != VISIBLE) onChangedWhileHidden?.invoke()
            }
        }.start()
    }

    /**
     * Called when the clock changed (another cover preset) while the face was hidden. Android shows the
     * screenshot it took as the face went away for a moment when the face comes back, which would flash
     * the old clock.
     */
    var onChangedWhileHidden: (() -> Unit)? = null

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
            drawPlaced(canvas, w, h, location[0] - placement.dx * w, location[1] - placement.dy * h)
            // Measured where Samsung's lock screen shows them: no template offset or resize.
            CoverWidgets.draw(canvas, widgets, w, h, location[0].toFloat(), location[1].toFloat())
        } else {
            // Drawn inside a moving/scaled card that represents the whole cover: lay out in the view's own
            // coordinates so the clock moves and scales with the card.
            val w = width.toFloat()
            val h = height.toFloat()
            drawPlaced(canvas, w, h, -placement.dx * w, -placement.dy * h)
            CoverWidgets.draw(canvas, widgets, w, h, 0f, 0f)
        }
        // Only a drawn frame schedules the next one: off screen, the ticking stops by itself.
        scheduleSecondTick()
    }

    /** The clock on a [w] x [h] cover drawn at ([offX], [offY]), at the size Samsung shows it. */
    private fun drawPlaced(canvas: Canvas, w: Float, h: Float, offX: Float, offY: Float) {
        val p = placement
        if (p.size == 1f) {
            renderer.draw(canvas, layout, Date(), w, h, offX, offY)
            return
        }
        val saved = canvas.save()
        canvas.scale(p.size, p.size, p.pivotX * w - offX, p.pivotY * h - offY)
        renderer.draw(canvas, layout, Date(), w, h, offX, offY)
        canvas.restoreToCount(saved)
    }
}
