package io.github.avengerdeveloper.flipfix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Banking apps often refuse to run while any accessibility service is on. FlipFix's service is already
 * off whenever the phone is open; this covers apps opened on the cover screen while folded.
 *
 *  - An app the user marked "pause for this app" → FlipFix switches its service off as soon as the app
 *    opens on the cover, until the screen next turns off.
 *  - An app that looks financial (name or package) and hasn't been answered yet → FlipFix asks once with
 *    a notification: "Pause FlipFix for <app>?" [Pause for this app] [Not needed].
 */
object BankGuard {

    private const val PREFS = "bank_guard"
    private const val KEY_PAUSED = "paused_apps"
    private const val KEY_NOT_NEEDED = "not_needed_apps"
    private const val CHANNEL = "bank_guard"
    const val ACTION_PAUSE = "io.github.avengerdeveloper.flipfix.action.PAUSE_FOR_APP"
    const val ACTION_NOT_NEEDED = "io.github.avengerdeveloper.flipfix.action.NOT_NEEDED"
    const val EXTRA_PACKAGE = "package"

    /** Apps known to work with accessibility services on (no need to ask). */
    private val NEVER_ASK = setOf(
        "com.samsung.android.spay", // Samsung Wallet: tested with FlipFix
        "com.google.android.apps.walletnfcrel",
    )
    private val FINANCE_WORDS = listOf(
        "bank", "banking", "finance", "financial", "fin", "pay", "wallet", "credit", "money", "invest",
        "trading", "trade", "broker", "crypto", "card", "loan", "cash",
    )

    /** Packages asked about since the screen last turned on (ask at most once per screen-on). */
    private val askedThisScreenOn = mutableSetOf<String>()

    fun onScreenOff() = askedThisScreenOn.clear()

    fun pausedApps(context: Context): Set<String> = prefs(context).getStringSet(KEY_PAUSED, emptySet())!!.toSet()

    fun forgetAll(context: Context) = prefs(context).edit().remove(KEY_PAUSED).remove(KEY_NOT_NEEDED).apply()

    /** Set from the app's "Choose banking apps" list (bank names rarely contain obvious keywords). */
    fun setPausedApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PAUSED, packages).apply()
        Log.i(CoverState.TAG, "bank guard: paused apps set to $packages")
    }

    /** Called by the accessibility service when a real app screen opens on the cover. */
    fun onAppOpenedOnCover(context: Context, pkg: String) {
        when {
            pkg in pausedApps(context) -> suspendFor(context, pkg)
            pkg in NEVER_ASK || pkg in notNeeded(context) || pkg in askedThisScreenOn -> Unit
            looksFinancial(context, pkg) -> {
                askedThisScreenOn += pkg
                ask(context, pkg)
            }
        }
    }

    /** Switch the accessibility service off now; the fold rule leaves it off until the screen turns off. */
    fun suspendFor(context: Context, pkg: String) {
        CoverState.suspendedFor = pkg
        AccessibilityToggle.set(context, false, "paused for $pkg")
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun looksFinancial(context: Context, pkg: String): Boolean {
        val words = (pkg.lowercase().split('.', '_') + label(context, pkg).lowercase().split(' ', '-', '_'))
            .filter { it.isNotBlank() }
        return words.any { w -> FINANCE_WORDS.any { f -> w == f || (f.length >= 4 && w.contains(f)) } }
    }

    private fun ask(context: Context, pkg: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            Log.w(CoverState.TAG, "cannot ask about $pkg: notifications not allowed for FlipFix")
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Banking apps", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks whether FlipFix should pause itself while a banking app is open"
            },
        )
        val name = label(context, pkg)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_flipfix)
            .setContentTitle("Looks like you opened $name")
            .setContentText("Pause FlipFix while it's open? Banking apps may refuse to run otherwise.")
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Pause for this app", action(context, ACTION_PAUSE, pkg)).build())
            .addAction(Notification.Action.Builder(null, "Not needed", action(context, ACTION_NOT_NEEDED, pkg)).build())
            .build()
        nm.notify(pkg.hashCode(), notification)
        Log.i(CoverState.TAG, "asked about $pkg ($name)")
    }

    private fun action(context: Context, action: String, pkg: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + pkg).hashCode(),
        Intent(context, BankGuardReceiver::class.java).setAction(action).putExtra(EXTRA_PACKAGE, pkg),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun answer(context: Context, action: String, pkg: String) {
        val key = if (action == ACTION_PAUSE) KEY_PAUSED else KEY_NOT_NEEDED
        val p = prefs(context)
        p.edit().putStringSet(key, p.getStringSet(key, emptySet())!! + pkg).apply()
        context.getSystemService(NotificationManager::class.java).cancel(pkg.hashCode())
        Log.i(CoverState.TAG, "bank guard: $pkg -> ${if (action == ACTION_PAUSE) "pause" else "not needed"}")
        if (action == ACTION_PAUSE) suspendFor(context, pkg)
    }

    private fun notNeeded(context: Context) = prefs(context).getStringSet(KEY_NOT_NEEDED, emptySet())!!

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Handles the two buttons on the "Pause FlipFix for <app>?" notification. */
class BankGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(BankGuard.EXTRA_PACKAGE) ?: return
        val action = intent.action ?: return
        BankGuard.answer(context, action, pkg)
    }
}
