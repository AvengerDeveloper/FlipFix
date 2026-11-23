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
                noteUnlock()
                walletHandle.hide("unlocked")
                maybeLaunch("unlock detected")
            } else if (SystemClock.uptimeMillis() < unlockPollDeadline) {
                handler.postDelayed(this, UNLOCK_POLL_MS)
            }
        }
    }

    private var lastLaunchAt = 0L

    private val walletHandle: WalletHandle = WalletHandle(this, { CoverState.coverDisplayId(displayManager) }) {
        // Counted as over the lock screen at once: a lock-screen check finishing a moment later would
        // otherwise replace the face, which goes in front of the sheet and closes it (seen on a Flip8).
        CoverState.appOverLockScreen = WalletQuickPay.PACKAGE
        walletHandle.hide("swiped up")
        WalletQuickPay.open(this, CoverState.coverDisplayId(displayManager), "lock-screen handle")
    }

    /**
     * The cover's notification shade is pulled down. SystemUI announces it (SHADE_EXPANDED, SHADE_COLLAPSED), and
     * Wallet hides its own handle on the same broadcasts; the shade raises no window event.
     */
    private var shadeOpen = false

    /** Samsung's preset picker is open over the lock screen (from a long press). */
    private var pickerOpen = false

    /**
     * FlipFix's Wallet handle (WalletHandle) shows while Samsung's lock screen is in front on the lit cover: the
     * face then waits behind it, which makes Wallet hide its own.
     */
    private fun updateWalletHandle(reason: String) {
        val wanted = CoverState.isEnabled(this) && WalletQuickPay.handleWanted(this) &&
            CoverState.appOverLockScreen == null && !shadeOpen && !pickerOpen &&
            keyguardManager.isKeyguardLocked && CoverState.isCoverOn(displayManager, powerManager)
        if (wanted) walletHandle.show(reason) else walletHandle.hide(reason)
    }

    /**
     * Whether Wallet shows its own cover handle (its Quick access > Cover screen switch, kept in Wallet's private
     * settings) is learned from Samsung's cover home: Wallet shows its handle whenever that home is in front, and
     * the handle's window raises an event each time it shows. Seen: on. FlipFix swiped away to Samsung's widgets
     * and no handle there after [WALLET_LOOK_MS]: off. FlipFix's own handles follow (CoverState.walletOnCover).
     */
    private fun noteWalletOnCover(on: Boolean, why: String) {
        if (CoverState.setWalletOnCover(this, on)) {
            Log.i(CoverState.TAG, "Wallet's own cover handle is ${if (on) "on" else "off"} ($why): FlipFix's follows")
            updateWalletHandle("Wallet's handle ${if (on) "on" else "off"}")
        }
    }

    private val walletRecheck = Runnable { updateWalletHandle("screen on, cover lit") }

    /** When Wallet's own handle last showed on the cover (uptime). */
    private var walletHandleSeenAt = 0L

    /** The swipe-away (CoverState.dismissedAt) the pending [walletLook] is for. */
    private var walletLookFor = 0L

    private val walletLook = Runnable {
        val stillThere = CoverState.dismissed && CoverState.dismissedAt == walletLookFor && !shadeOpen &&
            !keyguardManager.isKeyguardLocked && CoverState.isCoverOn(displayManager, powerManager)
        if (stillThere && walletHandleSeenAt < walletLookFor) {
            Log.d(CoverState.TAG, "no Wallet handle on Samsung's widgets after ${WALLET_LOOK_MS} ms")
            noteWalletOnCover(false, "Samsung's widgets showed without it")
        }
    }

    /**
     * FlipFix was just swiped away and Samsung's widgets are in front: Wallet shows its handle now if its switch is
     * on. Looked at once per swipe-away, and only with this service on for a while: Wallet only reports its
     * windows once it knows an accessibility service is on.
     */
    private fun lookForWalletHandle() {
        val dismissedAt = CoverState.dismissedAt
        val now = SystemClock.uptimeMillis()
        if (dismissedAt == walletLookFor || now - dismissedAt > WALLET_LOOK_AFTER_SWIPE_MS ||
            now - connectedAt < WALLET_LOOK_AFTER_CONNECT_MS || !WalletQuickPay.isAvailable(this)
        ) return
        walletLookFor = dismissedAt
        handler.removeCallbacks(walletLook)
        handler.postDelayed(walletLook, WALLET_LOOK_MS)
    }

    private var connectedAt = 0L

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
                    noteUnlock()
                    CoverState.dismissed = false
                    CoverState.appOverLockScreen = null
                    walletHandle.hide("unlocked")
                    maybeLaunch("unlocked")
                }
                Intent.ACTION_SCREEN_ON -> {
                    maybeLaunch("screen on")
                    updateWalletHandle("screen on")
                    // Woken again right after going off, the cover can read as off for up to ~150 ms after this
                    // broadcast (Flip8): looked at again once it's lit.
                    WALLET_RECHECKS_MS.forEach { handler.postDelayed(walletRecheck, it) }
                    scheduleLockScreenCheck()
                }
                SHADE_EXPANDED, SHADE_COLLAPSED -> {
                    shadeOpen = intent.action == SHADE_EXPANDED
                    // Wallet hides its handle for the shade too.
                    if (shadeOpen) handler.removeCallbacks(walletLook)
                    updateWalletHandle(if (shadeOpen) "shade opened" else "shade closed")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    CoverState.dismissed = false
                    CoverState.appOverLockScreen = null
                    shadeOpen = false
                    pickerOpen = false
                    walletHandle.hide("screen off")
                    handler.removeCallbacks(walletLook)
                    handler.removeCallbacks(walletRecheck)
                    handler.removeCallbacks(unlockPoll)
                    handler.removeCallbacks(lockScreenCheck)
                    edge.hide()
                    edge.releaseImages()
                    CoverState.widgetsSnapshot = null
                    freshFaceBehindLockScreen()
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
            addAction(SHADE_EXPANDED)
            addAction(SHADE_COLLAPSED)
        }
        // These are system broadcasts (SystemUI's shade ones unprotected); EXPORTED is required for them to be
        // delivered reliably.
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
        connectedAt = SystemClock.uptimeMillis()
        Log.i(CoverState.TAG, "accessibility service connected")
        maybeLaunch("service connected")
        updateWalletHandle("service connected")
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

    /** When the cover last unlocked (uptime), as first seen by the unlock poll or Android's broadcast. */
    @Volatile private var unlockedAt = 0L

    private fun noteUnlock() {
        val now = SystemClock.uptimeMillis()
        // One unlock is reported by both, a moment apart: keep the first.
        if (now - unlockedAt > UNLOCK_GUARD_MS) unlockedAt = now
    }

    /**
     * How far to trust a lock-screen snapshot taken at [shotAt] while [stamp] was current. A swipe up moves
     * Samsung's lock screen for a moment before Android reports the unlock, so a snapshot from just before an
     * unlock may show the clock on its way out (seen on a Flip6: the matched clock fitted at 0.20 instead of
     * 0.99, and its match was lost); one from just before the cover went off may be fading out. Those keep
     * only a sure match (CoverFaceMatcher.Doubt.LookAgain): a quick unlock right after switching presets
     * still gets the new preset's clock. Runs on the matcher's thread: waits, if need be, until an unlock
     * that close would have been reported.
     */
    private fun snapshotDoubt(shotAt: Long, stamp: String): CoverFaceMatcher.Doubt {
        val wait = shotAt + UNLOCK_GUARD_MS - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        return when {
            CoverFaceMatcher.presetStamp(this) != stamp -> CoverFaceMatcher.Doubt.Discard("the preset changed meanwhile")
            !CoverState.isCoverOn(displayManager, powerManager) -> CoverFaceMatcher.Doubt.LookAgain("the cover went off")
            unlockedAt - shotAt in 0..UNLOCK_GUARD_MS ->
                CoverFaceMatcher.Doubt.LookAgain("the cover unlocked ${unlockedAt - shotAt} ms after it")
            else -> CoverFaceMatcher.Doubt.None
        }
    }

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
        CoverState.appOverLockScreen?.let {
            // The snapshot would show that app; looked at again once the lock screen is back (lockScreenBack).
            Log.d(CoverState.TAG, "lock screen not checked: $it is over it")
            return
        }
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
                    val shotAt = SystemClock.uptimeMillis()
                    Thread {
                        val retry = CoverFaceMatcher.match(applicationContext, shot, at, stamp, newTry) {
                            snapshotDoubt(shotAt, stamp)
                        }
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

        if (pkg == WalletQuickPay.PACKAGE && !isAppScreen) {
            // Wallet's own handle, shown over Samsung's cover home.
            walletHandleSeenAt = SystemClock.uptimeMillis()
            Log.d(CoverState.TAG, "Wallet's own handle showed ($cls)")
            handler.removeCallbacks(walletLook)
            noteWalletOnCover(true, "seen over Samsung's cover home")
        } else if (!isSubHome) {
            // Something else came in front of Samsung's widgets: no telling what Wallet would have shown.
            handler.removeCallbacks(walletLook)
        }

        if (keyguardManager.isKeyguardLocked) {
            Log.d(CoverState.TAG, "locked cover window: $pkg / $cls")
            when {
                // The always-on display's own window as the cover dozes ("aod.a1" on a Flip8): no app.
                pkg == AOD_SERVICE && isAppScreen -> Unit
                // An app over the lock screen (Wallet's sheet, a call, an alarm). Wallet's own handle is a
                // floating window with a framework class, so it doesn't count.
                isAppScreen && pkg != null -> {
                    if (CoverState.appOverLockScreen != pkg) Log.d(CoverState.TAG, "$pkg is open over the lock screen")
                    CoverState.appOverLockScreen = pkg
                    walletHandle.hide("$pkg opened")
                }
                // Samsung's preset picker (long press): a window over the lock screen, no app.
                pkg == AOD_SERVICE -> {
                    pickerOpen = true
                    walletHandle.hide("preset picker opened")
                }
                // Samsung's lock screen in front again (its cover presentation): what was over it has closed.
                pkg == SYSTEMUI && !isSubHome -> {
                    if (pickerOpen) {
                        pickerOpen = false
                        updateWalletHandle("preset picker closed")
                    }
                    if (CoverState.appOverLockScreen != null) lockScreenBack()
                }
            }
        }

        when {
            // Samsung's widget pager does not report which page is showing (checked on One UI 9), so a
            // left-edge strip is how "swipe right to go back to FlipFix" is detected.
            isSubHome && keyguardManager.isKeyguardLocked -> {
                // Samsung shows its cover home a moment before the keyguard finishes going away.
                // A new unlock always starts fresh, whatever happened before.
                CoverState.dismissed = false
                walletHandle.hide("unlocking")
                startUnlockPoll()
            }
            isSubHome && CoverState.dismissed -> {
                edge.show()
                scheduleWidgetsCapture()
                lookForWalletHandle()
            }
            isSubHome -> maybeLaunch("Samsung cover home shown")
            isAppScreen -> {
                if (edge.isShown) {
                    Log.d(CoverState.TAG, "hiding edge strip: $pkg / $cls opened on cover")
                    edge.hide()
                }
                // An app opened on the unlocked cover after FlipFix was swiped away (from Samsung's widgets, its
                // app drawer or recents): leaving it, by Back, Home or closing it, returns to the face, as it does
                // for an app opened over the face. One UI Home's recents screen alone doesn't count, so backing out
                // of it stays on Samsung's widgets.
                if (CoverState.dismissed && pkg != null && pkg != ONE_UI_HOME && !keyguardManager.isKeyguardLocked) {
                    Log.d(CoverState.TAG, "$pkg opened since the swipe-away: the face comes back after it")
                    CoverState.dismissed = false
                }
                // Banking apps opened on the cover: pause FlipFix (or ask once). See BankGuard.
                if (pkg != null) BankGuard.onAppOpenedOnCover(this, pkg)
            }
        }
    }


    /**
     * The app that was over the lock screen has closed: catch up on what waited for it. The Wallet handle comes
     * back, a face whose clock changed meanwhile is replaced, and the lock screen is looked at if still needed.
     */
    private fun lockScreenBack() {
        val app = CoverState.appOverLockScreen
        CoverState.appOverLockScreen = null
        updateWalletHandle("$app closed")
        val face = CoverState.coverActivity?.get() as? CoverActivity
        if (face == null) {
            CoverState.placeFace(this, CoverState.coverDisplayId(displayManager), "$app closed")
        } else if (CoverState.faceOutdated && face.shown) {
            face.replace("its clock changed while $app was open")
        }
        scheduleLockScreenCheck()
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
        walletHandle.hide("service destroyed")
        runCatching { unregisterReceiver(receiver) }
        Log.i(CoverState.TAG, "accessibility service destroyed")
        super.onDestroy()
    }

    private fun startUnlockPoll() {
        unlockPollDeadline = SystemClock.uptimeMillis() + UNLOCK_POLL_WINDOW_MS
        handler.removeCallbacks(unlockPoll)
        handler.post(unlockPoll)
    }

    /**
     * As the cover goes off: a fresh face waits behind the lock screen, so the next unlock shows the face as
     * it is then, not Android's screenshot of it from now (a minute or a preset behind by then), and never
     * Samsung's widget page first. See CoverActivity.replace.
     */
    private fun freshFaceBehindLockScreen() {
        if (!CoverState.isEnabled(this)) return
        val face = CoverState.coverActivity?.get() as? CoverActivity
        if (face != null) {
            if (face.shown) face.replace("cover off")
        } else {
            CoverState.placeFace(this, CoverState.coverDisplayId(displayManager), "cover off")
        }
    }

    private fun maybeLaunch(reason: String) {
        val skip = when {
            !CoverState.isEnabled(this) -> "FlipFix switched off"
            CoverState.dismissed -> "dismissed until screen off"
            CoverState.showing -> "already showing"
            !CoverState.isCoverOn(displayManager, powerManager) -> "cover screen not on"
            keyguardManager.isKeyguardLocked -> "still locked"
            // The unlock was for a notification's app: that app goes in front. Swiping back still works.
            // (If the tap is reported a moment after this check, the face steps aside as it appears.)
            reason != EDGE_REASON && CoverState.notificationJustOpened() -> "a notification's app is opening"
            else -> null
        }
        if (skip != null) {
            Log.d(CoverState.TAG, "not launching ($reason): $skip")
            // No face behind the lock screen (FlipFix was just installed or restarted, or the phone was
            // open): the unlock would show Samsung's widget page until FlipFix launched one. Place one now.
            if (skip == "still locked" && CoverState.coverActivity?.get() == null && !CoverState.dismissed) {
                CoverState.placeFace(this, CoverState.coverDisplayId(displayManager), reason)
            }
            return
        }
        // Several triggers fire for one unlock (poll, USER_PRESENT, Samsung's home); launch once.
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchAt < RELAUNCH_GUARD_MS) return
        lastLaunchAt = now
        if (reason == EDGE_REASON) CoverState.edgeReturnAt = now
        handler.removeCallbacks(unlockPoll)
        // From the edge peek, the card hands over by itself once FlipFix has drawn (EdgeReturn.awaitFace).
        if (reason != EDGE_REASON) edge.hide()
        val coverId = CoverState.coverDisplayId(displayManager)
        // CoverActivity stays alive in the background (singleTask), so this usually just brings it forward,
        // with Android's screenshot of it showing until it has drawn.
        val intent = Intent(this, CoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val outdated = CoverState.faceOutdated
        val options = if (outdated) {
            // That screenshot shows the old clock: skip it. The face then appears as soon as it has drawn
            // (without an animation, so Samsung's widget page under it doesn't show through a fade).
            intent.putExtra(CoverState.EXTRA_REDRAWN, true)
            ActivityOptions.makeCustomAnimation(this, 0, 0)
        } else {
            ActivityOptions.makeBasic()
        }
        options.setLaunchDisplayId(coverId)
        try {
            startActivity(intent, options.toBundle())
            Log.i(CoverState.TAG, "launched cover face on display $coverId ($reason${if (outdated) ", redrawn" else ""})")
        } catch (t: Throwable) {
            Log.e(CoverState.TAG, "launch failed ($reason)", t)
        }
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        /** SystemUI's broadcasts as the cover's notification shade opens and closes (One UI 9). */
        const val SHADE_EXPANDED = "com.android.systemui.subscreen.EXPANDED"
        const val SHADE_COLLAPSED = "com.android.systemui.subscreen.COLLAPSED"
        /** Samsung's AOD app, which also shows the cover's preset picker. */
        const val AOD_SERVICE = "com.samsung.android.app.aodservice"
        /** One UI Home, whose recents screen also opens on the cover. */
        const val ONE_UI_HOME = "com.sec.android.app.launcher"
        /** Wallet shows its handle within ~50 ms of Samsung's home coming forward (Flip8, Wallet 6.8.67). */
        const val WALLET_LOOK_MS = 1_500L
        /** Samsung's widgets come forward once the swipe-away's card has covered the face. */
        const val WALLET_LOOK_AFTER_SWIPE_MS = 3_000L
        const val WALLET_LOOK_AFTER_CONNECT_MS = 1_000L
        val WALLET_RECHECKS_MS = longArrayOf(300L, 1_000L)
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
        /** Samsung's swipe-up takes about 300 ms to unlock, and Android's report of it up to ~150 ms more. */
        const val UNLOCK_GUARD_MS = 700L
        /** After picking a preset, Samsung's picker closes and the lock screen redraws first. */
        const val PRESET_SWITCH_CHECK_DELAY_MS = 700L
    }
}
