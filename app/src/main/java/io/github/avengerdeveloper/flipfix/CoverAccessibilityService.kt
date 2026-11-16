package io.github.avengerdeveloper.flipfix

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.Date

/**
 * Opens the cover face on the cover display whenever Samsung's unlocked cover home would otherwise show:
 *  - when Samsung's SubHomeActivity comes to the front on the cover (fires a moment before the unlock
 *    completes, so we poll the keyguard briefly and launch the instant it clears),
 *  - right after unlock (ACTION_USER_PRESENT), as a backup,
 *  - when the cover wakes while still unlocked (ACTION_SCREEN_ON with no keyguard),
 *  - when this service connects (covers an unlock that beat the service binding).
 *
 * After the user swipes FlipFix away to Samsung's widgets, a strip on the left edge of the cover lets a
 * swipe to the right bring FlipFix back, with an interactive peek (see EdgeReturn).
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
    private val edge: EdgeReturn = EdgeReturn(this, { CoverState.coverDisplayId(displayManager) }) {
        CoverState.dismissed = false
        // The full-screen card stays until FlipFix has drawn underneath it (or a timeout), then fades.
        edge.awaitFace()
        maybeLaunch(EDGE_REASON)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    CoverState.dismissed = false
                    maybeLaunch("unlocked")
                }
                Intent.ACTION_SCREEN_ON -> {
                    maybeLaunch("screen on")
                    scheduleLockScreenCheck()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    CoverState.dismissed = false
                    handler.removeCallbacks(unlockPoll)
                    handler.removeCallbacks(lockScreenCheck)
                    edge.hide()
                    edge.releaseImages()
                    CoverState.widgetsSnapshot = null
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
        CoverState.exitAnimator = { onCovered ->
            val face = edge.freshFace()
            if (face != null) edge.playExit(face, onCovered) else snapshotAndExit(onCovered)
        }
        CoverState.exitDrag = object : CoverState.ExitDrag {
            override fun prepare() = capture { it?.let(edge::offerFaceSnapshot) }
            override fun start() = edge.startExitDrag()
            override fun update(progress: Float) = edge.updateExitDrag(progress)
            override fun release(commit: Boolean, onCovered: () -> Unit) = edge.releaseExitDrag(commit, onCovered)
        }
        edge.onExitFinished = { scheduleWidgetsCapture() }
        Log.i(CoverState.TAG, "accessibility service connected")
        maybeLaunch("service connected")
        // The service is switched on as the cover lights up, often after SCREEN_ON was sent.
        scheduleLockScreenCheck()
        // A preset picked from the cover's long-press menu leaves the screen on: watch the switch itself.
        runCatching {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(CoverFaceMatcher.PRESET_SETTING), false, presetObserver,
            )
        }
    }

    private val presetObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            Log.d(CoverState.TAG, "cover preset switched")
            scheduleLockScreenCheck(PRESET_SWITCH_CHECK_DELAY_MS)
        }
    }

    private val lockScreenCheck = Runnable { checkLockScreen() }

    /** Snapshots taken since the cover woke (or the preset was switched). */
    private var lockChecksThisWake = 0

    /** After a cover preset switch, have a look at Samsung's lock screen once it has drawn. */
    private fun scheduleLockScreenCheck(delayMs: Long = LOCK_SCREEN_CHECK_DELAY_MS) {
        if (!keyguardManager.isKeyguardLocked || !CoverFaceMatcher.needsCheck(this)) return
        lockChecksThisWake = 0
        handler.removeCallbacks(lockScreenCheck)
        handler.postDelayed(lockScreenCheck, delayMs)
    }

    /**
     * One snapshot of Samsung's locked cover, compared with each preset's clock (CoverFaceMatcher). Right
     * after folding, Samsung's clock may still be animating in, so a miss is retried a little later
     * (up to [LOCK_SCREEN_RECHECK_DELAYS_MS]) while the cover stays awake and locked.
     */
    private fun checkLockScreen() {
        if (!keyguardManager.isKeyguardLocked || CoverState.showing ||
            !CoverState.isCoverOn(displayManager, powerManager) || !CoverFaceMatcher.needsCheck(this)
        ) return
        val stamp = CoverFaceMatcher.presetStamp(this)
        val newTry = lockChecksThisWake == 0
        val recheckIn = LOCK_SCREEN_RECHECK_DELAYS_MS.getOrNull(lockChecksThisWake)
        lockChecksThisWake++
        capture { shot ->
            when {
                shot == null -> Log.d(CoverState.TAG, "cover preset check: no snapshot")
                // Unlocked while the snapshot was taken: it may show the unlock animation, skip it.
                !keyguardManager.isKeyguardLocked -> shot.recycle()
                else -> {
                    val at = Date()
                    Thread {
                        val retry = CoverFaceMatcher.match(applicationContext, shot, at, stamp, newTry)
                        if (retry && recheckIn != null) handler.post {
                            // A newer wake or switch reset the count and scheduled its own check.
                            if (lockChecksThisWake > 0 && !handler.hasCallbacks(lockScreenCheck)) {
                                handler.postDelayed(lockScreenCheck, recheckIn)
                            }
                        }
                    }.start()
                }
            }
        }
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
            isSubHome && keyguardManager.isKeyguardLocked -> {
                // Samsung shows its cover home a moment before the keyguard finishes going away.
                // A new unlock always starts fresh, whatever happened before.
                CoverState.dismissed = false
                startUnlockPoll()
            }
            isSubHome && CoverState.dismissed -> {
                edge.show()
                scheduleWidgetsCapture()
            }
            isSubHome -> maybeLaunch("Samsung cover home shown")
            isAppScreen -> {
                if (edge.isShown) {
                    Log.d(CoverState.TAG, "hiding edge strip: $pkg / $cls opened on cover")
                    edge.hide()
                }
                // Banking apps opened on the cover: pause FlipFix (or ask once). See BankGuard.
                if (pkg != null) BankGuard.onAppOpenedOnCover(this, pkg)
            }
        }
    }


    /**
     * Swipe-away without a fresh snapshot: screenshot the cover and play the card exit with it.
     * Any failure falls back to stepping aside immediately.
     */
    private fun snapshotAndExit(onCovered: () -> Unit) {
        var done = false
        val once = { if (!done) { done = true; onCovered() } }
        capture { bitmap -> if (bitmap == null) once() else edge.playExit(bitmap, once) }
        // Never leave FlipFix stuck on screen if the screenshot never answers.
        handler.postDelayed({ once() }, SNAPSHOT_TIMEOUT_MS)
    }

    private var lastCaptureAt = 0L
    private var captureInFlight = false

    /**
     * Screenshot of the cover display only, kept in memory for the swipe animations; never saved or sent.
     * Android allows roughly one every 333 ms, so requests closer than that are skipped.
     */
    private fun capture(onResult: (Bitmap?) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (captureInFlight || now - lastCaptureAt < CAPTURE_INTERVAL_MS) return onResult(null)
        captureInFlight = true
        lastCaptureAt = now
        try {
            takeScreenshot(CoverState.coverDisplayId(displayManager), mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    captureInFlight = false
                    val buffer = result.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    buffer.close()
                    onResult(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    Log.w(CoverState.TAG, "cover snapshot failed ($errorCode)")
                    onResult(null)
                }
            })
        } catch (t: Throwable) {
            captureInFlight = false
            Log.w(CoverState.TAG, "cover snapshot unavailable", t)
            onResult(null)
        }
    }

    private val widgetsCapture = Runnable {
        if (CoverState.dismissed && edge.isIdleStrip) {
            capture { bitmap -> if (bitmap != null && edge.isIdleStrip) CoverState.widgetsSnapshot = bitmap }
        }
    }

    /** Capture Samsung's widget screen once it has settled, as the backdrop for the next swipe-away. */
    private fun scheduleWidgetsCapture() {
        handler.removeCallbacks(widgetsCapture)
        handler.postDelayed(widgetsCapture, WIDGETS_CAPTURE_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { contentResolver.unregisterContentObserver(presetObserver) }
        CoverState.exitAnimator = null
        CoverState.exitDrag = null
        edge.hide()
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
        // From the edge peek, the card hands over by itself once FlipFix has drawn (EdgeReturn.awaitFace).
        if (reason != EDGE_REASON) edge.hide()
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

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val EDGE_REASON = "edge swipe right"
        const val SNAPSHOT_TIMEOUT_MS = 400L
        const val CAPTURE_INTERVAL_MS = 350L
        const val WIDGETS_CAPTURE_DELAY_MS = 600L
        const val SUB_HOME = "SubHomeActivity"
        const val UNLOCK_POLL_MS = 16L
        const val UNLOCK_POLL_WINDOW_MS = 2_000L
        const val RELAUNCH_GUARD_MS = 500L
        /** Lets Samsung's lock screen finish waking from always-on display before the snapshot. */
        const val LOCK_SCREEN_CHECK_DELAY_MS = 350L
        /** After a miss, look again this much later (second and third snapshot of a wake: ~1.2 s, ~2.5 s). */
        val LOCK_SCREEN_RECHECK_DELAYS_MS = longArrayOf(850L, 1_300L)
        /** After picking a preset, Samsung's picker closes and the lock screen redraws first. */
        const val PRESET_SWITCH_CHECK_DELAY_MS = 700L
    }
}
