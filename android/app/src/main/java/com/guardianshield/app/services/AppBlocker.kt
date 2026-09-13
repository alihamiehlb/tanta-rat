package com.guardianshield.app.services

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.guardianshield.app.Config

/**
 * App blocker that prevents children from using restricted apps.
 *
 * Uses UsageStatsManager to detect the current foreground app every second.
 * When a blocked app is detected, shows a fullscreen overlay via SYSTEM_ALERT_WINDOW
 * with a "This app has been restricted" message.
 */
class AppBlocker(private val service: MainService) {

    companion object {
        private const val TAG = "GS_AppBlocker"
        private const val CHECK_INTERVAL_MS = 1000L // Check every 1 second
    }

    private val blockedApps = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var overlayView: View? = null
    private var currentBlockedApp: String? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkForegroundApp()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /**
     * Start the app blocker polling loop.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        handler.post(checkRunnable)
        Log.i(TAG, "App blocker started")
    }

    /**
     * Stop the app blocker.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        hideBlockOverlay()
        Log.i(TAG, "App blocker stopped")
    }

    /**
     * Update the list of blocked app package names.
     */
    fun updateBlocklist(apps: List<String>) {
        blockedApps.clear()
        blockedApps.addAll(apps)
        Log.i(TAG, "Blocklist updated: ${apps.size} apps blocked")
    }

    /**
     * Check the current foreground app against the blocklist.
     */
    private fun checkForegroundApp() {
        val foregroundPackage = getForegroundApp() ?: return

        if (foregroundPackage in blockedApps) {
            if (currentBlockedApp != foregroundPackage) {
                // New blocked app detected
                currentBlockedApp = foregroundPackage
                showBlockOverlay(foregroundPackage)
                service.emitBlockEvent("app", foregroundPackage)
                Log.i(TAG, "Blocked app detected: $foregroundPackage")
            }
        } else {
            if (currentBlockedApp != null) {
                // Child navigated away from blocked app
                currentBlockedApp = null
                hideBlockOverlay()
            }
        }
    }

    /**
     * Get the package name of the current foreground app using UsageStats.
     */
    private fun getForegroundApp(): String? {
        val usm = service.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 10_000L, // Last 10 seconds
            now
        )

        if (stats.isNullOrEmpty()) return null

        // Find the most recently used app
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    /**
     * Show a fullscreen blocking overlay using SYSTEM_ALERT_WINDOW.
     */
    private fun showBlockOverlay(packageName: String) {
        if (overlayView != null) return // Already showing

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Build overlay layout
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0111111"))
            setPadding(64, 64, 64, 64)
        }

        // Shield icon
        layout.addView(TextView(service).apply {
            text = "🛡️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER
        })

        // Title
        layout.addView(TextView(service).apply {
            text = "App Restricted"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        })

        // Message
        val appName = try {
            val pm = service.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        layout.addView(TextView(service).apply {
            text = "\"$appName\" has been restricted by your parent.\nPlease use another app."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })

        // Home button
        layout.addView(TextView(service).apply {
            text = "🏠  Go Home"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            setBackgroundColor(Color.parseColor("#4F8CFF"))
            setOnClickListener {
                // Launch home screen
                val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                service.startActivity(homeIntent)
            }
        })

        // Window params for fullscreen overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(layout, params)
            overlayView = layout
            Log.d(TAG, "Block overlay shown for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show block overlay", e)
        }
    }

    /**
     * Hide the blocking overlay.
     */
    private fun hideBlockOverlay() {
        overlayView?.let {
            try {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay", e)
            }
            overlayView = null
        }
    }
}
