package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickAccessibilityService? = null
    }

    private var remoteMode = false

    fun enableRemoteMode() {
        remoteMode = true
        Log.d("QuickDial", "✅ Remote mode ON")
    }

    fun disableRemoteMode() {
        remoteMode = false
        Log.d("QuickDial", "❌ Remote mode OFF")
    }

    fun isRemoteMode(): Boolean = remoteMode

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("QuickDial", "Service ready - waiting for server")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) return
        Log.d("QuickDial", "Tap: $x, $y")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (!remoteMode) return
        Log.d("QuickDial", "Swipe: $startX,$startY → $endX,$endY")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun openNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performSwipe(540f, 0f, 540f, 800f)
        } else {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun typeText(text: String) {
        if (!remoteMode) return
        Log.d("QuickDial", "Type: $text")
        val root = rootInActiveWindow ?: return
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    fun makeCall(number: String, context: Context) {
        // Encode special characters (* and #) for USSD codes
        val encodedNumber = Uri.encode(number)
        Log.d("QuickDial", "Calling: $number → Encoded: $encodedNumber")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun tapByText(text: String): Boolean {
        if (!remoteMode) return false
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
}
