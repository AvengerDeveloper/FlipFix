package dev.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.Display

/**
 * Bound by the system for as long as notification access is granted, so it is FlipFix's always-on part.
 *
 * Fold rule: the accessibility service is switched ON when the cover screen turns on (phone folded and
 * awake) and OFF as soon as the main screen turns on (phone opened). Banking apps on the main screen
 * therefore never see an accessibility service.
 *
 * Samsung hides the cover display from ordinary apps, so waking the cover screen produces no display
 * callback here; SCREEN_ON/OFF broadcasts are what trigger a re-check in that case.
 *
 * Later it will also feed notification icons to the cover face.
 */
class FlipNotificationListener : NotificationListenerService() {

    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = applyFoldRule("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = applyFoldRule("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = applyFoldRule("display $displayId changed")
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyFoldRule(intent.action?.substringAfterLast('.') ?: "broadcast")
            // Display state can lag the broadcast slightly; check again shortly after.
            handler.postDelayed({ applyFoldRule("recheck") }, RECHECK_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        displayManager = getSystemService(DisplayManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Protected system broadcasts; EXPORTED is required for them to be delivered reliably.
        registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        applyFoldRule("listener connected")
    }

    override fun onListenerDisconnected() {
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
            runCatching { unregisterReceiver(screenReceiver) }
        }
        handler.removeCallbacksAndMessages(null)
        super.onListenerDisconnected()
    }

    private fun applyFoldRule(reason: String) {
        val mainOn = CoverState.isMainOn(displayManager)
        val coverOn = CoverState.isCoverOn(displayManager, powerManager)
        val snapshot = "main=${onOff(mainOn)} cover=${onOff(coverOn)} interactive=${powerManager.isInteractive} " +
            "visibleDisplays=" + displayManager.displays.joinToString { "${it.displayId}:${displayStateName(it.state)}" } +
            " display1=" + (displayManager.getDisplay(1)?.let { displayStateName(it.state) } ?: "hidden")
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            Log.d(CoverState.TAG, "fold rule ($reason): $snapshot")
        }
        when {
            !CoverState.isEnabled(this) -> AccessibilityToggle.set(this, false, "FlipFix switched off")
            mainOn -> AccessibilityToggle.set(this, false, "main screen on ($reason)")
            coverOn -> AccessibilityToggle.set(this, true, "cover screen on ($reason)")
            // Both screens off: leave as is.
        }
    }

    private fun onOff(on: Boolean) = if (on) "ON" else "off"

    private fun displayStateName(state: Int) = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        else -> "state$state"
    }

    private companion object {
        const val RECHECK_MS = 400L
    }
}
