package io.github.avengerdeveloper.flipfix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * FlipFix's settings page on the main screen, in the shape of a One UI settings page: large title with a
 * one-line status, then grouped cards. Setup rows show whether each permission is in place and fix it
 * when tapped.
 */
class MainActivity : Activity() {

    private val dp get() = resources.displayMetrics.density
    private fun Int.dp() = (this * dp).toInt()
    private fun color(id: Int) = getColor(id)

    private lateinit var status: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var notifAccessRow: Row
    private lateinit var secureRow: Row
    private lateinit var notifRow: Row
    private lateinit var helperRow: Row
    private lateinit var bankRow: Row
    private lateinit var forgetRow: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 0, 16.dp(), 32.dp())
        }

        // Large title + status line (One UI's expanded app bar).
        page.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(color(R.color.ff_text))
            setPadding(12.dp(), 56.dp(), 12.dp(), 0)
        })
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.ff_text_secondary))
            setPadding(12.dp(), 6.dp(), 12.dp(), 32.dp())
        }
        page.addView(status)

        // Main switch.
        enabledSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                CoverState.setEnabled(this@MainActivity, checked)
                if (!checked) {
                    AccessibilityToggle.set(this@MainActivity, false, "switched off in app")
                    CoverState.finishCover()
                }
                refresh()
            }
        }
        page.addView(card(
            Row("Keep cover video after unlock", "Your cover screen stays as you set it when you unlock while folded")
                .trailing(enabledSwitch)
                .onClick { enabledSwitch.toggle() },
        ))

        // Setup.
        page.addView(header("Setup"))
        notifAccessRow = Row("Notification access", "Shows your notification icons and music on the cover")
            .onClick { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        secureRow = Row("Switch itself on and off", "Turns FlipFix's helper off when you open the phone")
            .onClick { showAdbGrant() }
        notifRow = Row("Notifications", "Only to ask about banking apps")
            .onClick { requestNotifications() }
        page.addView(card(notifAccessRow, secureRow, notifRow))

        // Cover screen.
        page.addView(header("Cover screen"))
        helperRow = Row("Accessibility helper", "")
        val timeoutRow = Row("Screen timeout", "Follows Samsung's cover screen timeout. Tap to change.")
            .trailing(chevron())
            .onClick { openCoverSettings() }
        val tileRow = Row("Quick Settings tile", "Turn FlipFix on or off from Quick Settings")
            .trailing(chevron())
            .onClick { addTile() }
        val coverRows = mutableListOf(helperRow, timeoutRow, tileRow)
        if (WalletQuickPay.isInstalled(this)) {
            val walletSwitch = Switch(this).apply {
                isChecked = CoverState.walletHandleOn(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> CoverState.setWalletHandleOn(this@MainActivity, checked) }
            }
            coverRows += Row(
                "Wallet handle when locked",
                "Samsung hides its swipe-up Wallet handle while FlipFix is on. FlipFix shows its own in the same place.",
            ).trailing(walletSwitch).onClick { walletSwitch.toggle() }
        }
        page.addView(card(*coverRows.toTypedArray()))

        // Banking apps.
        page.addView(header("Banking apps"))
        bankRow = Row("Pause FlipFix for…", "")
            .trailing(chevron())
            .onClick { chooseBankingApps() }
        forgetRow = Row("Forget banking-app choices", null, danger = true)
            .onClick { BankGuard.forgetAll(this); refresh() }
        page.addView(card(bankRow, forgetRow))

        // About.
        page.addView(header("About"))
        page.addView(card(
            Row("Test version", "Expect bugs · use at your own risk")
                .trailing(chevron())
                .onClick { showTestVersionNotice() },
        ))

        page.addView(TextView(this).apply {
            text = "No internet access · nothing leaves your phone"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(R.color.ff_text_secondary))
            gravity = Gravity.CENTER
            setPadding(24.dp(), 28.dp(), 24.dp(), 0)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page, MATCH_PARENT, WRAP_CONTENT)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val enabled = CoverState.isEnabled(this)
        enabledSwitch.setOnCheckedChangeListener(null)
        enabledSwitch.isChecked = enabled // may have changed from the tile
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            CoverState.setEnabled(this, checked)
            if (!checked) {
                AccessibilityToggle.set(this, false, "switched off in app")
                CoverState.finishCover()
            }
            refresh()
        }

        val notifAccess = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, FlipNotificationListener::class.java))
        val canToggle = AccessibilityToggle.canToggle(this)
        val canNotify = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        notifAccessRow.status(notifAccess)
        secureRow.status(canToggle)
        notifRow.status(canNotify)

        val missing = listOf(notifAccess, canToggle).count { !it } // notifications are optional
        status.text = when {
            !enabled -> "Off"
            missing > 0 -> "Setup needed"
            else -> "On · works whenever the phone is folded"
        }
        status.setTextColor(color(if (enabled && missing > 0) R.color.ff_needed else R.color.ff_text_secondary))

        helperRow.summary(
            if (AccessibilityToggle.isEnabled(this)) "On now"
            else "Off now · turns on by itself when you fold the phone",
        )


        val paused = BankGuard.pausedApps(this).map { BankGuard.label(this, it) }.sorted()
        bankRow.summary(
            if (paused.isEmpty()) "None chosen. FlipFix also asks when an app looks like a bank."
            else paused.joinToString(", "),
        )
        forgetRow.view.visibility = if (paused.isEmpty()) View.GONE else View.VISIBLE
        forgetRow.divider?.visibility = forgetRow.view.visibility
    }

    // ---- Actions ----

    /** The one permission Android only grants from a computer (or an on-phone ADB tool). */
    private fun showAdbGrant() {
        if (AccessibilityToggle.canToggle(this)) return
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog.Builder(this)
            .setTitle("One-time setup")
            .setMessage(
                "Android only allows this from a computer, or from an ADB app using Wireless debugging. " +
                    "Run once:\n\n$command",
            )
            .setPositiveButton("Copy command") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("FlipFix setup", command))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun requestNotifications() {
        if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /**
     * Samsung's cover screen timeout page; if this firmware doesn't have it (or no longer lets other apps open
     * it), the Cover screen settings page, then Settings itself.
     */
    private fun openCoverSettings() {
        for (action in listOf(COVER_TIMEOUT_ACTION, COVER_SETTINGS_ACTION, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    /** Ask the system to add the FlipFix tile to Quick Settings. */
    private fun addTile() {
        getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(this, FlipFixTileService::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_flipfix),
            mainExecutor,
        ) { }
    }

    /** Tick the apps FlipFix should pause itself for when they open on the cover screen. */
    private fun chooseBankingApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val paused = BankGuard.pausedApps(this)
        val checked = BooleanArray(apps.size) { apps[it].first in paused }
        AlertDialog.Builder(this)
            .setTitle("Pause FlipFix for these apps")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                BankGuard.setPausedApps(this, apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Building blocks ----

    /** The short form of DISCLAIMER.md. */
    private fun showTestVersionNotice() {
        AlertDialog.Builder(this)
            .setTitle("Test version")
            .setMessage(
                "FlipFix is experimental. It was built and tested by one developer on one Galaxy Z Flip8 and " +
                    "relies on undocumented One UI behaviour, so expect bugs, and a Samsung update can break it " +
                    "at any time.\n\n" +
                    "It's provided as is, without warranty: you use it at your own risk, and the developer isn't " +
                    "responsible for any problems with your phone.\n\n" +
                    "To stop it, unfold the phone, use the Quick Settings tile, or uninstall the app.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun header(title: String) = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(R.color.ff_text_secondary))
        setPadding(20.dp(), 28.dp(), 20.dp(), 10.dp())
    }

    /** Rounded group of rows with hairline dividers between them. */
    private fun card(vararg rows: Row): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(color(R.color.ff_card))
            cornerRadius = 26 * dp
        }
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                val line = View(context).apply { setBackgroundColor(color(R.color.ff_divider)) }
                addView(line, LinearLayout.LayoutParams(MATCH_PARENT, 1.dp().coerceAtLeast(1)).apply {
                    marginStart = 20.dp(); marginEnd = 20.dp()
                })
                row.divider = line
            }
            addView(row.view)
        }
    }

    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(color(R.color.ff_text_secondary))
    }

    /** One settings row: title, optional summary, optional trailing view, ripple when tappable. */
    private inner class Row(title: String, summary: String?, danger: Boolean = false) {
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 64.dp()
            setPadding(20.dp(), 14.dp(), 18.dp(), 14.dp())
        }
        private val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val titleView = TextView(this@MainActivity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(color(if (danger) R.color.ff_danger else R.color.ff_text))
        }
        private val summaryView = TextView(this@MainActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(R.color.ff_text_secondary))
            setPadding(0, 2.dp(), 0, 0)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        private var statusIcon: ImageView? = null
        var divider: View? = null

        init {
            texts.addView(titleView)
            texts.addView(summaryView)
            view.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            summary(summary)
        }

        fun summary(text: String?) {
            summaryView.text = text
            summaryView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun trailing(v: View) = apply {
            view.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 16.dp() })
        }

        /** Setup state: blue check when done, orange ring (and tappable) when it still needs doing. */
        fun status(ok: Boolean) {
            val icon = statusIcon ?: ImageView(this@MainActivity).also { statusIcon = it; trailing(it) }
            icon.setImageResource(if (ok) R.drawable.ic_status_ok else R.drawable.ic_status_needed)
            icon.imageTintList = ColorStateList.valueOf(color(if (ok) R.color.ff_ok else R.color.ff_needed))
            icon.contentDescription = if (ok) "Done" else "Needs setup"
            view.isClickable = !ok
            view.foreground = if (ok) null else ripple()
        }

        fun onClick(action: () -> Unit) = apply {
            view.setOnClickListener { action() }
            view.foreground = ripple()
        }

        private fun ripple() = RippleDrawable(ColorStateList.valueOf(color(R.color.ff_ripple)), null, null)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        /** Samsung's cover screen timeout page (AOD app; exported, no permission needed). */
        const val COVER_TIMEOUT_ACTION = "com.samsung.android.app.aodservice.settings.CoverScreenTimeoutSetting"
        /** Samsung's Cover screen settings page (AOD app; exported, no permission needed). */
        const val COVER_SETTINGS_ACTION = "com.samsung.android.app.aodservice.intent.action.COVER_SCREEN_SETTING_INTERNAL"
    }
}
