package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QuickAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle events here later
    }

    override fun onInterrupt() {
        // Handle interrupt
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("QuickDial", "Accessibility Service Connected")
    }

    // Simulate tap at coordinates
    fun performTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Simulate swipe
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Pull down notification panel
    fun openNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performSwipe(540f, 0f, 540f, 800f)
        } else {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    // Go home
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Go back
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // Open recent apps
    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    // Find and tap a view by text
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    // Find and tap a view by ID
    fun tapById(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }
}
