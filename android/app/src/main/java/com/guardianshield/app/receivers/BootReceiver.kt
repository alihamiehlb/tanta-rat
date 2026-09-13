package com.guardianshield.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardianshield.app.Config
import com.guardianshield.app.services.MainService

/**
 * Boot receiver that auto-starts the GuardianShield service
 * when the device is rebooted.
 *
 * Ensures persistent protection without manual intervention.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GS_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Device booted — starting GuardianShield service")

        // Load config to check if the app has been set up
        Config.init(context)
        if (!Config.isConfigured()) {
            Log.w(TAG, "App not configured yet — skipping auto-start")
            return
        }

        // Start the main foreground service
        val serviceIntent = Intent(context, MainService::class.java)
        context.startForegroundService(serviceIntent)

        Log.i(TAG, "MainService started after boot")
    }
}
