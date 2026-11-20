package io.github.avengerdeveloper.flipfix

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Samsung Wallet's quick-pay sheet (its "Quick access" cards), opened the way Samsung's cover handle opens it. */
object WalletQuickPay {
    const val PACKAGE = "com.samsung.android.spay"

    /**
     * Wallet's public link to the sheet. Wallet reads the path's first segment ("sidekey" for the side key), and
     * crashes on a link without one (Wallet 6.8.67).
     */
    private const val SHEET_LINK = "samsungwallet://simplepay/swipeup"

    /** What Samsung's cover handle sends: Wallet then opens the sheet as a cover swipe-up (its launch type 5003). */
    private const val EXTRA_COVER_SWIPE_UP = "extra_from_coverscreen_swipeup"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the sheet on [displayId] (the cover), or Wallet itself if the sheet can't be opened. */
    fun open(context: Context, displayId: Int, reason: String) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val sheet = Intent(Intent.ACTION_VIEW, Uri.parse(SHEET_LINK))
            .setPackage(PACKAGE)
            .putExtra(EXTRA_COVER_SWIPE_UP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(sheet, options)
            Log.i(CoverState.TAG, "opened Wallet's quick-pay sheet on display $displayId ($reason)")
            return
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "Wallet's quick-pay sheet didn't open ($reason), opening Wallet", t)
        }
        val app = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: run {
            Log.w(CoverState.TAG, "Samsung Wallet not installed")
            return
        }
        try {
            context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            Log.i(CoverState.TAG, "opened Wallet on display $displayId ($reason)")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "could not open Wallet ($reason)", t)
        }
    }
}

/**
 * Samsung Wallet's swipe-up handle on the locked cover, drawn by FlipFix.
 *
 * Wallet shows its own handle only while Samsung's home is the cover's top app ("hide reason is Home Package Not
 * Running" in its log, One UI 9). With FlipFix's face waiting behind the lock screen, the face is the top app, so
 * Wallet hides it. This one sits where Samsung's does and looks like it: a light bar peeking from the bottom edge.
 * Dragged up, it follows the finger and opens Wallet's quick-pay sheet, as Samsung's does.
 */
class WalletHandle(
    private val service: AccessibilityService,
    private val coverDisplayId: () -> Int,
    private val onSwipeUp: () -> Unit,
) {
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null

    val isShown get() = root != null

    fun show(reason: String) {
        if (root != null) return
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(coverDisplayId()) ?: return
        try {
            val ctx = service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val windowManager = ctx.getSystemService(WindowManager::class.java)
            val density = ctx.resources.displayMetrics.density
            // Samsung's handle, measured on a Flip8 cover 948 px wide: 256 px wide, 102 px from the left edge,
            // its top 13 px above the bottom edge. Scaled to this cover's width.
            val scale = windowManager.currentWindowMetrics.bounds.width() / REFERENCE_WIDTH
            val barWidth = (BAR_WIDTH * scale).roundToInt()
            val peek = (BAR_PEEK * scale).roundToInt()
            val pad = (TOUCH_PAD_DP * density).roundToInt()
            val windowHeight = (TOUCH_HEIGHT_DP * density).roundToInt()
            val lift = windowHeight - peek

            val bar = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(BAR_COLOR)
                    cornerRadius = BAR_RADIUS * scale
                }
                translationY = lift.toFloat()
            }
            val view = FrameLayout(ctx).apply {
                // Taller than its window: the part below the screen's edge is what a drag pulls into view.
                addView(bar, FrameLayout.LayoutParams(barWidth, windowHeight + peek).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = pad
                })
                setOnTouchListener(Drag(bar, lift.toFloat(), DRAG_COMMIT_DP * density))
            }
            val lp = WindowManager.LayoutParams(
                barWidth + 2 * pad,
                windowHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (BAR_LEFT * scale).roundToInt() - pad
                y = 0
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "FlipFix Wallet handle"
            }
            windowManager.addView(view, lp)
            wm = windowManager
            root = view
            Log.d(CoverState.TAG, "Wallet handle shown ($reason)")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "could not show the Wallet handle", t)
        }
    }

    fun hide(reason: String) {
        val view = root ?: return
        runCatching { wm?.removeView(view) }
        root = null
        wm = null
        Log.d(CoverState.TAG, "Wallet handle hidden ($reason)")
    }

    /** The bar follows an upward drag (resisting a little); past [commitPx] on release, Wallet opens. */
    private inner class Drag(private val bar: View, private val rest: Float, private val commitPx: Float) :
        View.OnTouchListener {
        private var downY = 0f
        private var committedHint = false

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val dy = (downY - ev.rawY).coerceAtLeast(0f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    committedHint = false
                    bar.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    bar.translationY = (rest - dy * DRAG_FOLLOW).coerceAtLeast(0f)
                    if (dy >= commitPx && !committedHint) {
                        committedHint = true
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } else if (dy < commitPx) {
                        committedHint = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dy >= commitPx) {
                        bar.animate().translationY(0f).alpha(0f).setDuration(SETTLE_MS).start()
                        onSwipeUp()
                    } else {
                        settle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> settle()
            }
            return true
        }

        private fun settle() {
            bar.animate().translationY(rest).alpha(1f).setDuration(SETTLE_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private companion object {
        const val REFERENCE_WIDTH = 948f
        const val BAR_WIDTH = 256f
        const val BAR_LEFT = 102f
        const val BAR_PEEK = 13f
        const val BAR_RADIUS = 10f
        /** Samsung's handle reads about #D4D4D6 over a dark wallpaper. */
        const val BAR_COLOR = 0xFFD6D6D8.toInt()
        /** Room around the bar that also takes the drag, so it's easy to catch. */
        const val TOUCH_PAD_DP = 16f
        const val TOUCH_HEIGHT_DP = 44f
        const val DRAG_COMMIT_DP = 40f
        const val DRAG_FOLLOW = 0.6f
        const val SETTLE_MS = 180L
    }
}
