package io.github.avengerdeveloper.flipfix

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Icon
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display

/** Shared, in-process state for the cover face. All FlipFix components run in one process. */
object CoverState {
    /** True while CoverActivity is on screen. */
    @Volatile var showing = false

    /** Set when the user swipes FlipFix away to reach Samsung's widgets; cleared when the screen turns off. */
    @Volatile var dismissed = false

    /** One icon per app with a notification, newest first (what Samsung's cover shows top-left). */
    data class NotificationIcon(val packageName: String, val icon: Icon)

    @Volatile var notificationIcons: List<NotificationIcon> = emptyList()
        private set
    private val notificationObservers = CopyOnWriteArraySet<() -> Unit>()

    fun publishNotificationIcons(icons: List<NotificationIcon>) {
        if (icons == notificationIcons) return
        notificationIcons = icons
        notificationObservers.forEach { it() }
    }

    fun observeNotificationIcons(observer: () -> Unit) = notificationObservers.add(observer)
    fun stopObservingNotificationIcons(observer: () -> Unit) = notificationObservers.remove(observer)

    /** The live cover face, if any (it stays alive in the background between uses). */
    @Volatile var coverActivity: WeakReference<Activity>? = null

    /** Close the cover face completely, e.g. when FlipFix is switched off. */
    fun finishCover() {
        coverActivity?.get()?.finish()
        coverActivity = null
    }

    const val TAG = "FlipFix"

    /** User's master switch (main screen toggle). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * The cover screen: the other built-in display. On Flip 5/6 it is display 1, named "Built-in Screen"
     * like the main one. Note Samsung flags it FLAG_PRESENTATION, so that flag must NOT be used to skip
     * displays. Virtual displays (casting, screen recording, DeX) have other names.
     */
    fun coverDisplay(dm: DisplayManager): Display? {
        val mainName = dm.getDisplay(Display.DEFAULT_DISPLAY)?.name
        val candidates = dm.displays.filter {
            it.displayId != Display.DEFAULT_DISPLAY &&
                it.flags and Display.FLAG_PRIVATE == 0 &&
                it.name == mainName
        }
        // One UI 9 leaves the cover display out of getDisplays() for ordinary apps, but returns it by id.
        return candidates.firstOrNull { it.displayId == 1 } ?: candidates.firstOrNull()
            ?: dm.getDisplay(FALLBACK_COVER_DISPLAY_ID)?.takeIf { it.flags and Display.FLAG_PRIVATE == 0 }
    }

    /** Cover display id to launch on: the visible one if Samsung shows it to us, else the Flip default (1). */
    fun coverDisplayId(dm: DisplayManager): Int = coverDisplay(dm)?.displayId ?: FALLBACK_COVER_DISPLAY_ID

    /**
     * Phone folded and awake. Samsung hides the cover display from ordinary apps, so when it isn't visible,
     * infer it: the device is interactive but the main screen is not on.
     */
    fun isCoverOn(dm: DisplayManager, pm: PowerManager): Boolean {
        coverDisplay(dm)?.let { return it.state == Display.STATE_ON }
        return pm.isInteractive && !isMainOn(dm)
    }

    fun isMainOn(dm: DisplayManager): Boolean = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private const val FALLBACK_COVER_DISPLAY_ID = 1
    private const val PREFS = "flipfix"
    private const val KEY_ENABLED = "enabled"
}
