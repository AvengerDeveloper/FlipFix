package dev.flipfix

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
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

        root.addView(Switch(this).apply {
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
        })

        status = TextView(this).apply { textSize = 15f; gravity = Gravity.START }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        setContentView(root)
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

        status.text = buildString {
            appendLine(check(notificationAccess) + "Notification access")
            appendLine(check(canToggle) + "Secure-settings permission (one-time ADB grant)")
            if (!canToggle) {
                appendLine("    adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            appendLine()
            appendLine("Accessibility service right now: " + if (a11yOn) "on" else "off")
            appendLine("It turns on by itself when you fold the phone and off when you open it.")
        }
    }

    private fun check(ok: Boolean) = if (ok) "✓  " else "✗  "
}
