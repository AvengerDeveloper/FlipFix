package dev.flipfix

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Opens the cover face on the cover display whenever Samsung's unlocked cover home would otherwise show:
 *  - when Samsung's SubHomeActivity comes to the front on the cover (fires a moment before the unlock
 *    completes, so we poll the keyguard briefly and launch the instant it clears),
 *  - right after unlock (ACTION_USER_PRESENT), as a backup,
 *  - when the cover wakes while still unlocked (ACTION_SCREEN_ON with no keyguard),
 *  - when this service connects (covers an unlock that beat the service binding).
 *
 * After the user swipes FlipFix away to Samsung's widgets, a thin invisible strip on the left edge of
 * the cover catches a swipe to the right and brings FlipFix back.
 *
 * An accessibility service is used because it may start activities from the background and draw
 * accessibility overlays on the cover display.
 */
class CoverAccessibilityService : AccessibilityService() {

    private lateinit var displayManager: DisplayManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private var unlockPollDeadline = 0L
    private val unlockPoll = object : Runnable {
        override fun run() {
            if (!keyguardManager.isKeyguardLocked) {
                maybeLaunch("unlock detected")
            } else if (SystemClock.uptimeMillis() < unlockPollDeadline) {
                handler.postDelayed(this, UNLOCK_POLL_MS)
            }
        }
    }

    private var lastLaunchAt = 0L
    private var edgeView: View? = null
    private var edgeWindowManager: WindowManager? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    CoverState.dismissed = false
                    maybeLaunch("unlocked")
                }
                Intent.ACTION_SCREEN_ON -> maybeLaunch("screen on")
                Intent.ACTION_SCREEN_OFF -> {
                    CoverState.dismissed = false
                    handler.removeCallbacks(unlockPoll)
                    hideEdge()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        displayManager = getSystemService(DisplayManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // These are protected system broadcasts; EXPORTED is required for them to be delivered reliably.
        registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        Log.i(CoverState.TAG, "accessibility service connected")
        maybeLaunch("service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.displayId != CoverState.coverDisplayId(displayManager)) return
        val pkg = event.packageName?.toString()
        // Our own windows (the edge strip, the cover face) also raise events; ignore them.
        if (pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onWindowChanged(event, pkg)
    }

    private fun onWindowChanged(event: AccessibilityEvent, pkg: String?) {
        val cls = event.className?.toString().orEmpty()
        val isSubHome = pkg == SYSTEMUI && cls.endsWith(SUB_HOME)
        // A real app screen has its own class name; floating windows such as Samsung Wallet's swipe-up
        // handle report a framework widget class (android.widget.FrameLayout) and sit over Samsung's home.
        val isAppScreen = pkg != SYSTEMUI && !cls.startsWith("android.")

        when {
            // Samsung's widget pager does not report which page is showing (checked on One UI 9), so a
            // left-edge strip is how "swipe right to go back to FlipFix" is detected.
            isSubHome && CoverState.dismissed -> {
                if (!keyguardManager.isKeyguardLocked) showEdge()
            }
            isSubHome && keyguardManager.isKeyguardLocked -> {
                // Samsung shows its cover home a moment before the keyguard finishes going away.
                startUnlockPoll()
            }
            isSubHome -> maybeLaunch("Samsung cover home shown")
            isAppScreen && edgeView != null -> {
                Log.d(CoverState.TAG, "hiding edge strip: $pkg / $cls opened on cover")
                hideEdge()
            }
        }
    }


    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideEdge()
        runCatching { unregisterReceiver(receiver) }
        Log.i(CoverState.TAG, "accessibility service destroyed")
        super.onDestroy()
    }

    private fun startUnlockPoll() {
        unlockPollDeadline = SystemClock.uptimeMillis() + UNLOCK_POLL_WINDOW_MS
        handler.removeCallbacks(unlockPoll)
        handler.post(unlockPoll)
    }

    private fun maybeLaunch(reason: String) {
        val skip = when {
            !CoverState.isEnabled(this) -> "FlipFix switched off"
            CoverState.dismissed -> "dismissed until screen off"
            CoverState.showing -> "already showing"
            !CoverState.isCoverOn(displayManager, powerManager) -> "cover screen not on"
            keyguardManager.isKeyguardLocked -> "still locked"
            else -> null
        }
        if (skip != null) {
            Log.d(CoverState.TAG, "not launching ($reason): $skip")
            return
        }
        // Several triggers fire for one unlock (poll, USER_PRESENT, Samsung's home); launch once.
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchAt < RELAUNCH_GUARD_MS) return
        lastLaunchAt = now
        handler.removeCallbacks(unlockPoll)
        hideEdge()
        val coverId = CoverState.coverDisplayId(displayManager)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(coverId)
        // CoverActivity stays alive in the background (singleTask), so this usually just brings it forward.
        val intent = Intent(this, CoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent, options.toBundle())
            Log.i(CoverState.TAG, "launched cover face on display $coverId ($reason)")
        } catch (t: Throwable) {
            Log.e(CoverState.TAG, "launch failed ($reason)", t)
        }
    }

    // ---- Left-edge strip: swipe right on Samsung's widgets to return to FlipFix ----

    private fun showEdge() {
        if (edgeView != null) return
        val display = displayManager.getDisplay(CoverState.coverDisplayId(displayManager)) ?: return
        try {
            val context = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = context.getSystemService(WindowManager::class.java)
            val density = context.resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                (EDGE_WIDTH_DP * density).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "FlipFix edge"
            }
            val view = View(context).apply { setOnTouchListener(EdgeSwipe((SWIPE_DP * density))) }
            wm.addView(view, params)
            edgeView = view
            edgeWindowManager = wm
            Log.d(CoverState.TAG, "edge strip shown")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "could not show edge strip", t)
        }
    }

    private fun hideEdge() {
        val view = edgeView ?: return
        runCatching { edgeWindowManager?.removeView(view) }
        edgeView = null
        edgeWindowManager = null
        Log.d(CoverState.TAG, "edge strip hidden")
    }

    private inner class EdgeSwipe(private val threshold: Float) : View.OnTouchListener {
        private var downX = 0f
        private var fired = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    fired = false
                    Log.d(CoverState.TAG, "edge strip touched")
                }
                MotionEvent.ACTION_MOVE -> if (!fired && event.rawX - downX > threshold) {
                    fired = true
                    CoverState.dismissed = false
                    maybeLaunch("edge swipe right")
                }
            }
            return true
        }
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val SUB_HOME = "SubHomeActivity"
        const val UNLOCK_POLL_MS = 16L
        const val UNLOCK_POLL_WINDOW_MS = 2_000L
        const val RELAUNCH_GUARD_MS = 500L
        const val EDGE_WIDTH_DP = 16
        const val SWIPE_DP = 40
    }
}
