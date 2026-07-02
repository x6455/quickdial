package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickAccessibilityService? = null
    }

    private var isRemoteMode = true
    private var overlayView: android.view.View? = null
    private var windowManager: android.view.WindowManager? = null

    fun setMode(remote: Boolean) {
        isRemoteMode = remote
        if (remote) {
            blockUserTouch()
        } else {
            releaseUserTouch()
        }
    }

    private fun blockUserTouch() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        overlayView = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }
        val params = android.view.WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }
            flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            height = android.view.WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager?.addView(overlayView, params)
    }

    private fun releaseUserTouch() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        blockUserTouch()
        Log.d("QuickDial", "Accessibility Service Connected - Remote Mode")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

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
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

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
}
