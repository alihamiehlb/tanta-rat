package com.guardianshield.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.guardianshield.app.Config
import com.guardianshield.app.commands.SoundPlayer
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Core foreground service — the central hub of GuardianShield.
 *
 * Responsibilities:
 * - Runs as a persistent foreground service with notification
 * - Manages Socket.IO connection to the Railway backend
 * - Coordinates screen capture, location tracking, and app blocking
 * - Routes incoming commands to appropriate handlers
 * - Auto-reconnects and survives app being swiped from recents
 */
class MainService : Service() {

    companion object {
        private const val TAG = "GS_MainService"
        private const val CHANNEL_ID = "guardianshield_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REMOTE_CONTROL = "com.guardianshield.REMOTE_CONTROL"
    }

    // Socket.IO client
    private var socket: Socket? = null

    // Sub-service managers
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var locationTracker: LocationTracker? = null
    private var appBlocker: AppBlocker? = null

    // Wakelock for persistence
    private var wakeLock: PowerManager.WakeLock? = null

    // Heartbeat handler
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            socket?.emit("heartbeat", JSONObject().put("deviceId", Config.deviceId))
            handler.postDelayed(this, 30_000L) // Every 30 seconds
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        Config.init(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")

        // Start as foreground service immediately
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Extract MediaProjection result from intent
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data: Intent? = intent?.getParcelableExtra("data")

        // Initialize Socket.IO connection
        initializeSocket()

        // Start screen capture if we have MediaProjection data
        if (resultCode != -1 && data != null) {
            screenCaptureManager = ScreenCaptureManager(this, resultCode, data)
        }

        // Start location tracker
        locationTracker = LocationTracker(this)

        // Start app blocker
        appBlocker = AppBlocker(this)

        return START_STICKY // Restart if killed
    }

    /**
     * Initialize Socket.IO connection to the Railway backend.
     */
    private fun initializeSocket() {
        try {
            val encToken = java.net.URLEncoder.encode(Config.AUTH_TOKEN, "UTF-8")
            val encDevId = java.net.URLEncoder.encode(Config.deviceId, "UTF-8")
            val encModel = java.net.URLEncoder.encode(Build.MODEL ?: "Unknown", "UTF-8")
            val encLabel = java.net.URLEncoder.encode(Config.deviceLabel ?: "Device", "UTF-8")
            val encOs    = java.net.URLEncoder.encode(Build.VERSION.RELEASE ?: "Android", "UTF-8")

            val options = IO.Options().apply {
                query = "token=$encToken&deviceId=$encDevId&model=$encModel&label=$encLabel&osVersion=$encOs"
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 10000
            }

            socket = IO.socket(URI.create("${Config.SERVER_URL}/device"), options)

            socket?.apply {
                on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, "Connected to server")
                    // Start sub-services
                    screenCaptureManager?.start()
                    locationTracker?.start()
                    appBlocker?.start()
                    // Send installed apps list
                    sendInstalledApps()
                    // Start heartbeat
                    handler.post(heartbeatRunnable)
                }

                on(Socket.EVENT_DISCONNECT) {
                    Log.w(TAG, "Disconnected from server")
                    handler.removeCallbacks(heartbeatRunnable)
                }

                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, "Connection error: ${args.firstOrNull()}")
                }

                // Handle remote commands
                on("command") { args ->
                    if (args.isNotEmpty()) {
                        handleCommand(args[0] as JSONObject)
                    }
                }

                // Handle touch/gesture commands from dashboard
                on("touch") { args ->
                    if (args.isNotEmpty()) {
                        handleTouchCommand(args[0] as JSONObject)
                    }
                }

                // Handle blocklist updates from dashboard
                on("blocklist_update") { args ->
                    if (args.isNotEmpty()) {
                        handleBlocklistUpdate(args[0] as JSONObject)
                    }
                }

                // Handle request for installed apps
                on("request_installed_apps") {
                    sendInstalledApps()
                }

                connect()
            }

            Log.i(TAG, "Socket.IO connecting to ${Config.SERVER_URL}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize socket", e)
        }
    }

    /**
     * Handle incoming commands from the dashboard.
     */
    private fun handleCommand(data: JSONObject) {
        val action = data.optString("action", "")
        Log.i(TAG, "Received command: $action")

        when (action) {
            "play_sound" -> SoundPlayer.play(this)
            "request_location" -> locationTracker?.requestNow()
            "lock_screen" -> {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                try { dpm.lockNow() } catch (e: Exception) {
                    Log.e(TAG, "Failed to lock screen", e)
                }
            }
        }
    }

    /**
     * Handle touch/gesture commands from the dashboard.
     * Routes to RemoteControlService via broadcast.
     */
    private fun handleTouchCommand(data: JSONObject) {
        val intent = Intent(ACTION_REMOTE_CONTROL).apply {
            putExtra("action", data.optString("action", ""))
            putExtra("x", data.optDouble("x", 0.0).toFloat())
            putExtra("y", data.optDouble("y", 0.0).toFloat())
            putExtra("x1", data.optDouble("x1", 0.0).toFloat())
            putExtra("y1", data.optDouble("y1", 0.0).toFloat())
            putExtra("x2", data.optDouble("x2", 0.0).toFloat())
            putExtra("y2", data.optDouble("y2", 0.0).toFloat())
            putExtra("duration", data.optLong("duration", 300L))
            putExtra("text", data.optString("text", ""))
        }
        sendBroadcast(intent)
    }

    /**
     * Handle blocklist updates from the dashboard.
     */
    private fun handleBlocklistUpdate(data: JSONObject) {
        Log.i(TAG, "Blocklist update received")
        val apps = data.optJSONArray("apps")
        val sites = data.optJSONArray("sites")

        if (apps != null) {
            val appList = mutableListOf<String>()
            for (i in 0 until apps.length()) {
                appList.add(apps.getString(i))
            }
            appBlocker?.updateBlocklist(appList)
        }

        if (sites != null) {
            val siteList = mutableListOf<String>()
            for (i in 0 until sites.length()) {
                siteList.add(sites.getString(i))
            }
            // Update VPN blocklist via broadcast
            val vpnIntent = Intent("com.guardianshield.UPDATE_SITE_BLOCKLIST").apply {
                putStringArrayListExtra("domains", ArrayList(siteList))
            }
            sendBroadcast(vpnIntent)
        }
    }

    // -- Data emission methods (called by sub-services) --

    /**
     * Send a screen frame to the server.
     */
    fun emitFrame(bytes: ByteArray, width: Int, height: Int) {
        socket?.emit("frame", JSONObject().apply {
            put("deviceId", Config.deviceId)
            put("width", width)
            put("height", height)
        }, bytes)
    }

    /**
     * Send a location update to the server.
     */
    fun emitLocation(location: Location) {
        socket?.emit("location", JSONObject().apply {
            put("deviceId", Config.deviceId)
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("bearing", location.bearing)
            put("altitude", location.altitude)
            put("timestamp", location.time)
        })
    }

    /**
     * Send a block event to the server.
     */
    fun emitBlockEvent(type: String, target: String) {
        socket?.emit("block_event", JSONObject().apply {
            put("deviceId", Config.deviceId)
            put("type", type)
            put("target", target)
            put("timestamp", System.currentTimeMillis())
        })
    }

    /**
     * Send the list of installed apps to the server.
     */
    private fun sendInstalledApps() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appArray = JSONArray()

            for (app in apps) {
                // Filter out system apps unless they have a launcher icon
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null
                if (!isSystemApp || hasLauncher) {
                    appArray.put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("appName", pm.getApplicationLabel(app).toString())
                    })
                }
            }

            socket?.emit("installed_apps", JSONObject().apply {
                put("deviceId", Config.deviceId)
                put("apps", appArray)
            })

            Log.i(TAG, "Sent ${appArray.length()} installed apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send installed apps", e)
        }
    }

    // -- Notification --

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GuardianShield Service",
            NotificationManager.IMPORTANCE_LOW // Low importance = no sound, minimal visual
        ).apply {
            description = "Keeps GuardianShield running in the background"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GuardianShield")
            .setContentText("Device Protected ✓")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    // -- Wakelock --

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GuardianShield::MainService"
        ).apply {
            acquire() // Held indefinitely while service runs
        }
    }

    // -- Lifecycle --

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed — scheduling restart")
        handler.removeCallbacks(heartbeatRunnable)
        screenCaptureManager?.stop()
        locationTracker?.stop()
        appBlocker?.stop()
        socket?.disconnect()
        wakeLock?.release()

        // Schedule restart for persistence
        val restartIntent = Intent(this, MainService::class.java)
        startForegroundService(restartIntent)

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed — service continues running")
        // Service is START_STICKY so it will be restarted by the system
        super.onTaskRemoved(rootIntent)
    }
}
