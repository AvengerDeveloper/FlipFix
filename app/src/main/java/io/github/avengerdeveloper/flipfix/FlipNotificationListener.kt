package io.github.avengerdeveloper.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
 * It also publishes the notification icons shown at the top-left of the cover face, refreshed only while the
 * cover is lit.
 */
class FlipNotificationListener : NotificationListenerService() {

    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""

    /** The cover as last seen, and when it last went off (uptime): tells whether the phone opened from it. */
    private var wasCoverOn = false
    private var coverOffAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayEvent("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = onDisplayEvent("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = onDisplayEvent("display $displayId changed")
    }

    private fun onDisplayEvent(reason: String) {
        if (screensOff) displayEventsWhileOff++
        applyFoldRule(reason)
    }

    // From screen off to screen on, counted and logged once as a screen comes on: what wakes FlipFix while
    // it has nothing to show.
    private var screensOff = false
    private var displayEventsWhileOff = 0
    private var notificationChangesWhileOff = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // A banking-app pause and a "swiped to widgets" dismissal last until the screen turns off.
                // Cleared here because this listener always runs (the accessibility service may be off).
                CoverState.suspendedFor = null
                CoverState.dismissed = false
                BankGuard.onScreenOff()
                screensOff = true
                displayEventsWhileOff = 0
                notificationChangesWhileOff = 0
            } else if (intent.action == Intent.ACTION_SCREEN_ON && screensOff) {
                screensOff = false
                Log.d(
                    CoverState.TAG,
                    "while the screens were off: $displayEventsWhileOff display changes, " +
                        "$notificationChangesWhileOff notification changes",
                )
            }
            applyFoldRule(intent.action?.substringAfterLast('.') ?: "broadcast")
            // Display state can lag the broadcast slightly; check again shortly after.
            handler.postDelayed({ applyFoldRule("recheck") }, RECHECK_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        displayManager = getSystemService(DisplayManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Added, removed and changed (on, off, doze...), asked for explicitly: the older call can also
            // deliver refresh-rate changes, which Android warns can come very often.
            displayManager.registerDisplayListener(
                mainExecutor,
                DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED or
                    DisplayManager.EVENT_TYPE_DISPLAY_CHANGED,
                displayListener,
            )
        } else {
            displayManager.registerDisplayListener(displayListener, handler)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Protected system broadcasts; EXPORTED is required for them to be delivered reliably.
        registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        applyFoldRule("listener connected")
        publishIcons()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = requestIcons()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (reason == REASON_CLICK) onNotificationOpened(sbn?.packageName)
        requestIcons()
    }

    /** A notification changed since the icons and the music pill were last published. */
    private var iconsStale = true
    private val publishIconsNow = Runnable { publishIcons() }

    /**
     * The icons and the music pill are only seen on the lit cover. While it's off (asleep, or the phone is
     * open), a notification change only marks them stale, and they're refreshed once as it lights up
     * (applyFoldRule) instead of reading every posted notification after each change. While it's on, a
     * burst of changes (a notification and its ranking update) is refreshed once.
     */
    private fun requestIcons() {
        iconsStale = true
        if (screensOff) notificationChangesWhileOff++
        if (!::displayManager.isInitialized || !CoverState.isCoverOn(displayManager, powerManager)) return
        handler.removeCallbacks(publishIconsNow)
        handler.postDelayed(publishIconsNow, ICONS_COALESCE_MS)
    }

    /**
     * A notification was tapped (and dismissed itself, as most do): its app opens, on the cover if the phone
     * is folded. FlipFix mustn't come forward over it after the unlock (see CoverAccessibilityService), and
     * if it already has (the unlock and the tap land within milliseconds of each other), it steps aside.
     * Notifications that stay posted when tapped don't report the tap, so they can't be followed.
     */
    private fun onNotificationOpened(pkg: String?) {
        CoverState.notificationOpenedAt = SystemClock.uptimeMillis()
        if (CoverState.showing) {
            Log.d(CoverState.TAG, "notification from $pkg opened: stepping aside")
            CoverState.stepAside()
        } else {
            Log.d(CoverState.TAG, "notification from $pkg opened")
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = requestIcons()

    /**
     * Mirrors Samsung's cover icons (checked on One UI 9): one icon per app, newest first, skipping
     * notifications with "min" importance (e.g. the ongoing "Battery protection" notification) and media
     * players (Samsung shows those in its Now bar, never as an icon).
     */
    private fun publishIcons() {
        handler.removeCallbacks(publishIconsNow)
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        iconsStale = false
        val ranking = currentRanking
        val r = Ranking()
        val icons = active
            .filter { sbn ->
                sbn.packageName != packageName &&
                    sbn.notification.smallIcon != null &&
                    !isMedia(sbn.notification) &&
                    (!ranking.getRanking(sbn.key, r) || r.importance >= NotificationManager.IMPORTANCE_LOW)
            }
            .groupBy { it.packageName }
            .map { (_, list) -> list.maxBy { it.postTime } }
            .sortedByDescending { it.postTime }
            .map { CoverState.NotificationIcon(it.packageName, it.notification.smallIcon) }
        if (icons.map { it.packageName } != CoverState.notificationIcons.map { it.packageName }) {
            Log.d(CoverState.TAG, "cover icons: ${icons.map { it.packageName }}")
        }
        CoverState.publishNotificationIcons(icons)

        // The players' own notifications (not SystemUI's Now bar mirror), for the music pill.
        val players = active.filter { it.packageName != "com.android.systemui" && isMedia(it.notification) }
        CoverState.publishMediaNotifications(
            CoverState.MediaNotifications(
                packages = players.map { it.packageName }.toSet(),
                sessions = players.mapNotNull {
                    it.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                }.toSet(),
            ),
        )
    }

    /**
     * Media players, plus Samsung's Now bar entries (e.g. SystemUI's "MediaOngoingActivity" mirror of the
     * playing track, "..._nowbar_ongoing_channel"): Samsung shows these in its Now bar, never as icons.
     */
    private fun isMedia(n: Notification): Boolean {
        val channel = n.channelId.orEmpty().lowercase()
        return n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true ||
            "ongoingactivity" in channel || "nowbar" in channel
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
        if (wasCoverOn && !coverOn) coverOffAt = SystemClock.uptimeMillis()
        wasCoverOn = coverOn
        if (coverOn && iconsStale) publishIcons()
        when {
            !CoverState.isEnabled(this) -> AccessibilityToggle.set(this, false, "FlipFix switched off")
            mainOn -> {
                CoverState.suspendedFor = null
                CoverState.dismissed = false
                // Opened with the cover lit, Samsung moves the cover's top task (the face, placed behind the
                // lock screen) to the main screen, where the unlock would show it. Closed now; the next cover
                // wake places a fresh one. Opened with the cover off, the face stays on the cover, ready.
                val coverJustOff = SystemClock.uptimeMillis() - coverOffAt < COVER_LIT_BEFORE_OPEN_MS
                if (coverJustOff && CoverState.coverActivity?.get() != null) {
                    Log.d(CoverState.TAG, "phone opened with the cover lit: face closed")
                    CoverState.finishCover()
                }
                AccessibilityToggle.set(this, false, "main screen on ($reason)")
            }
            CoverState.suspendedFor != null -> AccessibilityToggle.set(this, false, "paused for ${CoverState.suspendedFor}")
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
        /** Opening the phone turns the cover off about 60 ms before the main screen comes on (Flip8). */
        const val COVER_LIT_BEFORE_OPEN_MS = 2_000L
        const val ICONS_COALESCE_MS = 100L
    }
}
