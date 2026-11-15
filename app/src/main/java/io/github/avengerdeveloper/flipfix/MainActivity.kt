package io.github.avengerdeveloper.flipfix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Setup and status screen (main display). */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var bankList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
        })

        enabledSwitch = Switch(this).apply {
            text = "Keep cover video after unlock"
            textSize = 16f
            isChecked = CoverState.isEnabled(this@MainActivity)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, checked ->
                CoverState.setEnabled(this@MainActivity, checked)
                if (!checked) {
                    AccessibilityToggle.set(this@MainActivity, false, "switched off in app")
                    CoverState.finishCover()
                }
                refresh()
            }
        }
        root.addView(enabledSwitch)

        status = TextView(this).apply { textSize = 15f; gravity = Gravity.START }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        root.addView(TextView(this).apply {
            text = "Banking apps on the cover screen"
            textSize = 18f
            setPadding(0, pad, 0, pad / 3)
        })
        bankList = TextView(this).apply { textSize = 15f }
        root.addView(bankList)
        root.addView(Button(this).apply {
            text = "Choose banking apps…"
            setOnClickListener { chooseBankingApps() }
        })
        root.addView(Button(this).apply {
            text = "Forget banking-app choices"
            setOnClickListener {
                BankGuard.forgetAll(this@MainActivity)
                refresh()
            }
        })

        setContentView(root)

        // Needed for the one-time "Pause FlipFix for <banking app>?" question.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val listener = ComponentName(this, FlipNotificationListener::class.java)
        val notificationAccess = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listener)
        val canToggle = AccessibilityToggle.canToggle(this)
        val a11yOn = AccessibilityToggle.isEnabled(this)
        val canNotify = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        enabledSwitch.isChecked = CoverState.isEnabled(this) // may have changed from the tile

        status.text = buildString {
            appendLine(check(notificationAccess) + "Notification access")
            appendLine(check(canToggle) + "Secure-settings permission (one-time ADB grant)")
            if (!canToggle) {
                appendLine("    adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            appendLine(check(canNotify) + "Notifications (for the banking-app question)")
            appendLine()
            appendLine("Accessibility service right now: " + if (a11yOn) "on" else "off")
            appendLine("It turns on by itself when you fold the phone and off when you open it.")
            appendLine("Tip: add the FlipFix tile to Quick Settings to switch it on or off.")
        }

        val paused = BankGuard.pausedApps(this).map { BankGuard.label(this, it) }.sorted()
        bankList.text = if (paused.isEmpty()) {
            "None chosen yet. Tick your banking apps below; FlipFix also asks the first time you open an " +
                "app whose name looks like a banking app."
        } else {
            "FlipFix pauses itself for: " + paused.joinToString(", ")
        }
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

    private fun check(ok: Boolean) = if (ok) "✓  " else "✗  "
}
