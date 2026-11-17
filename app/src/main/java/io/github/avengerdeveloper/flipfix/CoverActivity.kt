package io.github.avengerdeveloper.flipfix

import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.lang.ref.WeakReference
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The unlocked cover face. Shows Samsung's own cover wallpaper (we are the wallpaper target) and asks
 * Samsung's video engine to resume, undoing the pause One UI 9 sends on unlock.
 *
 * Gestures: swipe left/right = hide FlipFix until the screen next turns off (reveals Samsung's widgets),
 * swipe up = Samsung Wallet. Samsung's own top-edge pull-down for notifications keeps working.
 */
class CoverActivity : Activity() {

    private lateinit var gestures: GestureDetector
    private lateinit var clock: CoverClockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (display?.displayId == Display.DEFAULT_DISPLAY) {
            // Only meant for the cover screen.
            finish()
            return
        }
        setContentView(R.layout.activity_cover)
        clock = findViewById(R.id.clock)
        clock.glass = findViewById(R.id.glass)
        // A hidden face is brought back with Android's screenshot of it showing first: after a preset
        // switch that would flash the old clock, so close it and start afresh next time.
        clock.onChangedWhileHidden = {
            if (!CoverState.showing) {
                Log.d(CoverState.TAG, "cover clock changed while hidden: closing the face")
                finish()
            }
        }
        gestures = GestureDetector(this, FlingListener())
        CoverState.coverActivity = WeakReference(this)
        hideNavigationBar()
    }

    /** Samsung shows a navigation bar (back/home/recents) over cover apps; the cover face has none. */
    private fun hideNavigationBar() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Samsung's cover windows (lock screen, widget home, notification panel) each carry the cover screen's own
     * timeout, Settings > Cover screen > Screen timeout, and Android uses it while they're on top. Without it the
     * cover face would stay on for the main screen's timeout instead. Read on every resume, so a changed setting
     * applies the next time the face shows.
     */
    private fun applyCoverTimeout() {
        val seconds = try {
            Settings.System.getInt(contentResolver, CoverState.COVER_TIMEOUT_SETTING)
        } catch (e: Exception) {
            Log.w(CoverState.TAG, "cover timeout unreadable, using the main screen's: $e")
            return
        }
        if (seconds <= 0) return
        val millis = seconds * 1000L
        try {
            // Hidden field, the same one Samsung's windows set; there is no public API for it.
            val field = WindowManager.LayoutParams::class.java.getField("userActivityTimeout")
            val attrs = window.attributes
            if (field.getLong(attrs) != millis) {
                field.setLong(attrs, millis)
                window.attributes = attrs
            }
            Log.d(CoverState.TAG, "cover timeout ${seconds}s")
        } catch (e: Exception) {
            Log.w(CoverState.TAG, "cover timeout not applied: $e")
        }
    }

    override fun onResume() {
        super.onResume()
        if (display?.displayId == Display.DEFAULT_DISPLAY || !CoverState.isEnabled(this)) {
            // Moved to the main screen when the phone was opened, or FlipFix was switched off.
            finish()
            return
        }
        if (CoverState.notificationJustOpened()) {
            // Brought forward by the unlock a moment before the tapped notification's app opened (the
            // two land within milliseconds): that app goes in front.
            Log.d(CoverState.TAG, "stepping aside on resume: a notification's app is opening")
            moveTaskToBack(true)
            return
        }
        CoverState.showing = true
        // Undo the slide-out from the last dismissal: FlipFix comes back in place, exactly where the edge
        // peek (or Samsung's lock screen) left the face.
        findViewById<View>(R.id.root).animate().cancel()
        findViewById<View>(R.id.root).apply { translationX = 0f; alpha = 1f }
        // Tell the edge peek once this face is really on screen, so its card can fade out on top of it.
        window.decorView.viewTreeObserver.registerFrameCommitCallback { CoverState.notifyFaceShown() }
        hideNavigationBar()
        applyCoverTimeout()
        clock.invalidate()
        clock.reloadStyleAsync()
        window.decorView.post { resumeVideo("resumed") }
        // Samsung sends its own pause at unlock; if we were already on top, ours may land first.
        // Re-send shortly after so the last word is always "resume".
        window.decorView.postDelayed({ if (CoverState.showing) resumeVideo("resumed +250ms") }, 250)
        window.decorView.postDelayed({ if (CoverState.showing) resumeVideo("resumed +700ms") }, 700)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resumeVideo("focused")
    }

    override fun onPause() {
        CoverState.showing = false
        super.onPause()
    }

    override fun onDestroy() {
        if (CoverState.coverActivity?.get() === this) CoverState.coverActivity = null
        super.onDestroy()
    }

    // Not finished on stop: staying alive in the background on the cover display makes the next
    // return (after unlock, or swiping back from the widgets) much faster than creating it again.

    /** Set by the fling listener when a swipe was recognised and acted on. */
    private var flingHandled = false

    // Swipe-away that follows the finger (see EdgeReturn): a leftward drag moves a snapshot card.
    private var exitDragging = false
    private var exitDecided = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    /**
     * Every touch feeds the swipe detector and also reaches the views (so the music pill's buttons get
     * taps). A leftward drag becomes the interactive swipe-away; other swipes are flings (right = widgets
     * without following the finger, up = Wallet). When a swipe is acted on, the views get a cancel.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val drag = CoverState.exitDrag
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; downAt = SystemClock.uptimeMillis()
                exitDragging = false; exitDecided = false
                drag?.prepare() // snapshot now, so the card is ready the moment a drag starts
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!exitDecided && hypot(dx, dy) > touchSlop) {
                    exitDecided = true
                    if (drag != null && dx < 0 && abs(dx) > abs(dy) * 1.2f) {
                        exitDragging = true
                        drag.start()
                        cancelChildren(ev)
                    }
                }
                if (exitDragging) {
                    drag?.update(-dx / (exitDistance()))
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (exitDragging) {
                exitDragging = false
                val dx = ev.rawX - downX
                val progress = (-dx / exitDistance()).coerceIn(0f, 1f)
                val velocity = -dx / (SystemClock.uptimeMillis() - downAt).coerceAtLeast(1) // px per ms
                val commit = progress > EXIT_COMMIT_PROGRESS ||
                    (velocity > EXIT_FLICK_DP_PER_MS * resources.displayMetrics.density && -dx > touchSlop * 2)
                if (commit) CoverState.dismissed = true
                (drag ?: return true).release(commit) { moveTaskToBack(true) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (exitDragging) {
                exitDragging = false
                drag?.release(false) {}
                return true
            }
        }
        if (exitDragging) return true

        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        if (flingHandled) {
            flingHandled = false
            cancelChildren(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** Drag this far (same as the edge peek: 60% of the width) for the card to reach its smallest. */
    private fun exitDistance() = window.decorView.width.coerceAtLeast(1) * 0.6f

    private fun resumeVideo(reason: String) {
        val token = window.decorView.windowToken ?: return
        try {
            WallpaperManager.getInstance(this).sendWallpaperCommand(token, CMD_RESUME, 0, 0, 0, null)
            Log.d(CoverState.TAG, "sent resume ($reason)")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "resume failed ($reason)", t)
        }
    }

    private fun dismiss() {
        CoverState.dismissed = true
        // The accessibility service snapshots this face into a card that covers the screen, then we step
        // aside underneath it and the card shrinks away to the left, revealing Samsung's widgets (the
        // reverse of the edge peek). FlipFix stays alive for a quick swipe back.
        val exit = CoverState.exitAnimator
        if (exit == null) {
            moveTaskToBack(true)
        } else {
            exit { moveTaskToBack(true) }
        }
    }

    private fun openWallet() {
        val launch = packageManager.getLaunchIntentForPackage(WALLET) ?: run {
            Log.w(CoverState.TAG, "Samsung Wallet not installed")
            return
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display?.displayId ?: return)
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
            Log.i(CoverState.TAG, "opened Wallet on display ${display?.displayId}")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "could not open Wallet on cover", t)
        }
    }

    private inner class FlingListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            val minDistance = resources.displayMetrics.density * MIN_SWIPE_DP
            return when {
                abs(dx) > abs(dy) && abs(dx) > minDistance -> { flingHandled = true; dismiss(); true }
                dy < 0 && abs(dy) > abs(dx) && abs(dy) > minDistance -> { flingHandled = true; openWallet(); true }
                else -> false
            }
        }
    }

    private companion object {
        const val CMD_RESUME = "samsung.android.wallpaper.resume"
        const val WALLET = "com.samsung.android.spay"
        const val MIN_SWIPE_DP = 48
        const val EXIT_COMMIT_PROGRESS = 0.33f
        const val EXIT_FLICK_DP_PER_MS = 0.4f
    }
}
