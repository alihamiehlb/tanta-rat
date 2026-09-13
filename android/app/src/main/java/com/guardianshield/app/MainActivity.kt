package com.guardianshield.app

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardianshield.app.receivers.DeviceAdminReceiver
import com.guardianshield.app.services.MainService
import com.guardianshield.app.services.SiteBlockerVpn

/**
 * Setup activity for GuardianShield.
 *
 * On first launch: walks through permission grants, then starts services.
 * On subsequent launches: checks if services are running and shows status.
 * The server URL and auth token are embedded at build time (see Config.kt).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GS_MainActivity"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var btnSetup: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPermLocation: TextView
    private lateinit var tvPermBgLocation: TextView
    private lateinit var tvPermUsageStats: TextView
    private lateinit var tvPermOverlay: TextView
    private lateinit var tvPermDeviceAdmin: TextView
    private lateinit var tvPermBattery: TextView
    private lateinit var tvPermAccessibility: TextView
    private lateinit var tvPermScreenCapture: TextView
    private lateinit var tvPermVpn: TextView

    private var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
    private var mediaProjectionData: Intent? = null

    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bgLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var deviceAdminLauncher: ActivityResultLauncher<Intent>

    private var currentStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        registerLaunchers()
        buildUI()
        updatePermissionStatus()

        // If already fully set up, just show status
        if (isFullySetup()) {
            tvStatus.text = "🟢 GuardianShield is active"
            btnSetup.text = "Running"
            btnSetup.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun registerLaunchers() {
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val granted = results.values.all { it }
            tvPermLocation.text = if (granted) "✅ Location Permission" else "❌ Location Permission"
            if (granted) proceedSetup()
        }

        bgLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            tvPermBgLocation.text = if (granted) "✅ Background Location" else "❌ Background Location"
            proceedSetup()
        }

        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                mediaProjectionResultCode = result.resultCode
                mediaProjectionData = result.data
                tvPermScreenCapture.text = "✅ Screen Capture"
                proceedSetup()
            } else {
                tvPermScreenCapture.text = "❌ Screen Capture (denied)"
                Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_LONG).show()
            }
        }

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                tvPermVpn.text = "✅ VPN Service"
                proceedSetup()
            } else {
                tvPermVpn.text = "❌ VPN Service (denied)"
            }
        }

        deviceAdminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            val isAdmin = isDeviceAdminActive()
            tvPermDeviceAdmin.text = if (isAdmin) "✅ Device Admin" else "❌ Device Admin"
            proceedSetup()
        }
    }

    private fun buildUI() {
        val scrollView = ScrollView(this).apply {
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF121212.toInt())
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Title
        tvTitle = TextView(this).apply {
            text = "🛡️ GuardianShield"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvTitle)

        tvSubtitle = TextView(this).apply {
            text = "Parental Control System"
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSubtitle)

        // Device fingerprint
        tvDeviceId = TextView(this).apply {
            text = "Device ID: ${Config.deviceId}\n${Config.deviceLabel}"
            textSize = 13f
            setTextColor(0xFF4F8CFF.toInt())
            setPadding(0, 0, 0, 24)
        }
        layout.addView(tvDeviceId)

        // Server info
        layout.addView(TextView(this).apply {
            text = "Server: ${Config.SERVER_URL}"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 32)
        })

        // Setup button
        btnSetup = Button(this).apply {
            text = "Start Setup"
            setOnClickListener { startSetupFlow() }
        }
        layout.addView(btnSetup)

        // Permission status section
        layout.addView(TextView(this).apply {
            text = "\nPermission Status:"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 32, 0, 16)
        })

        tvPermLocation = createStatusTextView("⬜ Location Permission")
        tvPermBgLocation = createStatusTextView("⬜ Background Location")
        tvPermUsageStats = createStatusTextView("⬜ Usage Stats Access")
        tvPermOverlay = createStatusTextView("⬜ Overlay Permission")
        tvPermDeviceAdmin = createStatusTextView("⬜ Device Admin")
        tvPermBattery = createStatusTextView("⬜ Battery Optimization")
        tvPermAccessibility = createStatusTextView("⬜ Accessibility Service")
        tvPermScreenCapture = createStatusTextView("⬜ Screen Capture")
        tvPermVpn = createStatusTextView("⬜ VPN Service")

        listOf(
            tvPermLocation, tvPermBgLocation, tvPermUsageStats,
            tvPermOverlay, tvPermDeviceAdmin, tvPermBattery,
            tvPermAccessibility, tvPermScreenCapture, tvPermVpn
        ).forEach { layout.addView(it) }

        // Status
        tvStatus = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xFF22C55E.toInt())
            setPadding(0, 32, 0, 0)
        }
        layout.addView(tvStatus)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun createStatusTextView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 8, 0, 8)
        }
    }

    private fun startSetupFlow() {
        if (!Config.isConfigured()) {
            Toast.makeText(this,
                "Server URL not configured. Rebuild APK with your Railway URL in Config.kt",
                Toast.LENGTH_LONG).show()
            return
        }
        currentStep = 0
        proceedSetup()
    }

    private fun proceedSetup() {
        when (currentStep) {
            0 -> { currentStep++; requestLocationPermission() }
            1 -> { currentStep++; requestBackgroundLocationPermission() }
            2 -> { currentStep++; requestUsageStatsPermission() }
            3 -> { currentStep++; requestOverlayPermission() }
            4 -> { currentStep++; requestDeviceAdmin() }
            5 -> { currentStep++; requestBatteryOptimization() }
            6 -> { currentStep++; requestAccessibilityService() }
            7 -> { currentStep++; requestScreenCapture() }
            8 -> { currentStep++; requestVpnPermission() }
            9 -> startServices()
        }
    }

    private fun requestLocationPermission() {
        if (hasLocationPermission()) {
            tvPermLocation.text = "✅ Location Permission"
            proceedSetup()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (hasBgLocationPermission()) {
            tvPermBgLocation.text = "✅ Background Location"
            proceedSetup()
        } else {
            bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun requestUsageStatsPermission() {
        if (hasUsageStatsPermission()) {
            tvPermUsageStats.text = "✅ Usage Stats Access"
            proceedSetup()
        } else {
            tvStatus.text = "Enable Usage Access for GuardianShield"
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            tvPermOverlay.text = "✅ Overlay Permission"
            proceedSetup()
        } else {
            tvStatus.text = "Enable overlay permission"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun requestDeviceAdmin() {
        if (isDeviceAdminActive()) {
            tvPermDeviceAdmin.text = "✅ Device Admin"
            proceedSetup()
        } else {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@MainActivity, DeviceAdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required for device protection and management.")
            }
            deviceAdminLauncher.launch(intent)
        }
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            tvPermBattery.text = "✅ Battery Optimization"
            proceedSetup()
        } else {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun requestAccessibilityService() {
        if (isAccessibilityServiceEnabled()) {
            tvPermAccessibility.text = "✅ Accessibility Service"
            proceedSetup()
        } else {
            tvStatus.text = "Enable GuardianShield in Accessibility settings"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            tvPermVpn.text = "✅ VPN Service"
            proceedSetup()
        }
    }

    private fun startServices() {
        Log.i(TAG, "All permissions granted — starting services")
        tvStatus.text = "🟢 GuardianShield is active"
        btnSetup.isEnabled = false
        btnSetup.text = "Running"

        // Start main foreground service
        val serviceIntent = Intent(this, MainService::class.java).apply {
            putExtra("resultCode", mediaProjectionResultCode)
            putExtra("data", mediaProjectionData)
        }
        startForegroundService(serviceIntent)

        // Start VPN for site blocking
        startService(Intent(this, SiteBlockerVpn::class.java))

        Toast.makeText(this, "GuardianShield active — device ${Config.deviceId}", Toast.LENGTH_LONG).show()
    }

    // -- Permission checks --

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBgLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/.services.RemoteControlService"
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(serviceName, ignoreCase = true)
    }

    private fun isFullySetup(): Boolean {
        return hasLocationPermission() && hasBgLocationPermission() &&
            hasUsageStatsPermission() && Settings.canDrawOverlays(this) &&
            isDeviceAdminActive() && isAccessibilityServiceEnabled()
    }

    private fun updatePermissionStatus() {
        tvPermLocation.text = if (hasLocationPermission()) "✅ Location Permission" else "❌ Location Permission"
        tvPermBgLocation.text = if (hasBgLocationPermission()) "✅ Background Location" else "❌ Background Location"
        tvPermUsageStats.text = if (hasUsageStatsPermission()) "✅ Usage Stats Access" else "❌ Usage Stats Access"
        tvPermOverlay.text = if (Settings.canDrawOverlays(this)) "✅ Overlay Permission" else "❌ Overlay Permission"
        tvPermDeviceAdmin.text = if (isDeviceAdminActive()) "✅ Device Admin" else "❌ Device Admin"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        tvPermBattery.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅ Battery Optimization" else "❌ Battery Optimization"
        tvPermAccessibility.text = if (isAccessibilityServiceEnabled()) "✅ Accessibility Service" else "❌ Accessibility Service"
        if (mediaProjectionData != null) tvPermScreenCapture.text = "✅ Screen Capture"
    }
}
