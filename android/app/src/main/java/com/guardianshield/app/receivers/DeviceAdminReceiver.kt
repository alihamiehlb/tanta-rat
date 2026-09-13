package com.guardianshield.app.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver for uninstall protection.
 *
 * When activated as a device administrator, prevents the app from being
 * casually uninstalled. The parent must deactivate device admin first
 * (which requires the parent's knowledge/PIN).
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "GS_DeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — uninstall protection active")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Warn before disabling — this is the last line of defense
        return "⚠️ Warning: Disabling device admin will remove GuardianShield's " +
            "uninstall protection. The app can then be uninstalled freely."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled — uninstall protection removed")
    }
}
