package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickAccessibilityService? = null
    }

    private var remoteMode = false
    private var overlayView: android.view.View? = null
    private var windowManager: android.view.WindowManager? = null
    private var touchBlocked = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun enableRemoteMode() {
        remoteMode = true
        mainHandler.post { blockTouch() }
        LogUtil.i("A11yService", "✅ Remote mode ENABLED")
    }

    fun disableRemoteMode() {
        remoteMode = false
        mainHandler.post { releaseTouch() }
        LogUtil.i("A11yService", "❌ Remote mode DISABLED")
    }

    fun isRemoteMode(): Boolean = remoteMode

    private fun blockTouch() {
        if (touchBlocked) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            
            overlayView = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                setOnTouchListener { _, _ -> true }
            }
            
            val params = android.view.WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                }
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP
                x = 0
                y = 80
            }
            
            windowManager?.addView(overlayView, params)
            touchBlocked = true
            LogUtil.i("A11yService", "Touch BLOCKED (status bar free)")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Block touch failed", e)
            touchBlocked = false
        }
    }

    private fun releaseTouch() {
        try {
            overlayView?.let { 
                windowManager?.removeView(it) 
                LogUtil.i("A11yService", "Touch RELEASED")
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Release touch failed", e)
        }
        overlayView = null
        windowManager = null
        touchBlocked = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // DO NOT block touch - wait for explicit remote mode ON command
        LogUtil.i("A11yService", "Service ready - waiting for commands")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            LogUtil.d("A11yService", "Window: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        LogUtil.w("A11yService", "Interrupted - releasing")
        mainHandler.post { releaseTouch() }
    }

    override fun onDestroy() {
        mainHandler.post { releaseTouch() }
        instance = null
        LogUtil.i("A11yService", "Destroyed")
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) return
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            LogUtil.d("A11yService", "Tap: ($x, $y)")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Tap failed", e)
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (!remoteMode) return
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
            LogUtil.d("A11yService", "Swipe: ($startX,$startY)→($endX,$endY)")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Swipe failed", e)
        }
    }

    fun openNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performSwipe(540f, 0f, 540f, 800f)
            } else {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Notifications failed", e)
        }
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun typeText(text: String) {
        if (!remoteMode) return
        try {
            val root = rootInActiveWindow ?: return
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Type failed", e)
        }
    }

    fun makeCall(number: String, context: Context) {
        try {
            val encodedNumber = Uri.encode(number)
            LogUtil.i("A11yService", "Call: $number → $encodedNumber")
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Call failed", e)
        }
    }
}
