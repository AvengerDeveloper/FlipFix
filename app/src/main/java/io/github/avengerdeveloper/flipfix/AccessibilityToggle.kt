package io.github.avengerdeveloper.flipfix

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Switches FlipFix's accessibility service on and off by editing the secure setting directly.
 * Needs WRITE_SECURE_SETTINGS (granted once over ADB). Other apps' services are left untouched.
 */
object AccessibilityToggle {

    fun canToggle(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean {
        val me = component(context)
        return enabledServices(context).any { ComponentName.unflattenFromString(it) == me }
    }

    /** Returns true if the setting now matches [enabled]. */
    fun set(context: Context, enabled: Boolean, reason: String): Boolean {
        if (isEnabled(context) == enabled) return true
        if (!canToggle(context)) {
            Log.w(CoverState.TAG, "cannot switch accessibility ($reason): WRITE_SECURE_SETTINGS not granted")
            return false
        }
        val me = component(context)
        val others = enabledServices(context).filter { ComponentName.unflattenFromString(it) != me }
        val updated = if (enabled) others + me.flattenToString() else others
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated.joinToString(":"))
        if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i(CoverState.TAG, "accessibility service ${if (enabled) "ON" else "OFF"} ($reason)")
        return true
    }

    private fun component(context: Context) = ComponentName(context, CoverAccessibilityService::class.java)

    private fun enabledServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }
}
