package com.guardianshield.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service for remote control.
 *
 * Receives touch commands (tap, swipe, type, navigation) via broadcast
 * from MainService and dispatches them as system gestures.
 * This is the same mechanism used by TeamViewer, AnyDesk, etc.
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        private const val TAG = "GS_RemoteControl"

        // Static reference for checking if service is running
        var instance: RemoteControlService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }

    private var commandReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        registerCommandReceiver()
    }

    /**
     * Register broadcast receiver for touch commands from MainService.
     */
    private fun registerCommandReceiver() {
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MainService.ACTION_REMOTE_CONTROL) return

                val action = intent.getStringExtra("action") ?: return
                Log.d(TAG, "Received command: $action")

                when (action) {
                    "tap" -> {
                        val x = intent.getFloatExtra("x", 0f)
                        val y = intent.getFloatExtra("y", 0f)
                        performTap(x, y)
                    }
                    "longPress" -> {
                        val x = intent.getFloatExtra("x", 0f)
                        val y = intent.getFloatExtra("y", 0f)
                        performLongPress(x, y)
                    }
                    "swipe" -> {
                        val x1 = intent.getFloatExtra("x1", 0f)
                        val y1 = intent.getFloatExtra("y1", 0f)
                        val x2 = intent.getFloatExtra("x2", 0f)
                        val y2 = intent.getFloatExtra("y2", 0f)
                        val duration = intent.getLongExtra("duration", 300L)
                        performSwipe(x1, y1, x2, y2, duration)
                    }
                    "type" -> {
                        val text = intent.getStringExtra("text") ?: ""
                        performType(text)
                    }
                    "back" -> performBack()
                    "home" -> performHome()
                    "recents" -> performRecents()
                    "scrollDown" -> performScroll(true)
                    "scrollUp" -> performScroll(false)
                }
            }
        }

        val filter = IntentFilter(MainService.ACTION_REMOTE_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    /**
     * Perform a tap gesture at the given coordinates.
     */
    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50) // 50ms tap
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Tap at ($x, $y)")
    }

    /**
     * Perform a long press gesture (600ms hold).
     */
    private fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600) // 600ms hold
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Long press at ($x, $y)")
    }

    /**
     * Perform a swipe gesture from (x1,y1) to (x2,y2).
     */
    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val safeDuration = duration.coerceIn(100, 2000) // Clamp between 100ms and 2s
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe ($x1,$y1) -> ($x2,$y2) over ${safeDuration}ms")
    }

    /**
     * Type text into the currently focused input field.
     * Uses AccessibilityNodeInfo.ACTION_SET_TEXT for reliable text input.
     */
    private fun performType(text: String) {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            // Get current text and append
            val currentText = focusedNode.text?.toString() ?: ""
            val newText = currentText + text

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            Log.d(TAG, "Typed: $text")
        } else {
            Log.w(TAG, "No focused input field for typing")
        }
    }

    /**
     * Perform global BACK action.
     */
    private fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Back")
    }

    /**
     * Perform global HOME action.
     */
    private fun performHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "Home")
    }

    /**
     * Perform global RECENTS action.
     */
    private fun performRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.d(TAG, "Recents")
    }

    /**
     * Scroll the current scrollable view up or down.
     */
    private fun performScroll(down: Boolean) {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val action = if (down) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollable.performAction(action)
            scrollable.recycle()
            Log.d(TAG, "Scroll ${if (down) "down" else "up"}")
        }
        root.recycle()
    }

    /**
     * Find the first scrollable node in the view tree.
     */
    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    // -- Required overrides --

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for remote control
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.w(TAG, "Accessibility service destroyed")
        commandReceiver?.let { unregisterReceiver(it) }
        instance = null
        super.onDestroy()
    }
}
