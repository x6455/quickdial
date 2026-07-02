package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.net.Uri
import android.os.Build
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

    fun enableRemoteMode() {
        remoteMode = true
        blockTouch()
        LogUtil.i("A11yService", "✅ Remote mode ENABLED - Touch blocked")
    }

    fun disableRemoteMode() {
        remoteMode = false
        releaseTouch()
        LogUtil.i("A11yService", "❌ Remote mode DISABLED - Phone free")
    }

    fun isRemoteMode(): Boolean = remoteMode

    private fun blockTouch() {
        if (touchBlocked) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            
            overlayView = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0)) // Nearly invisible
                setOnTouchListener { _, _ -> 
                    LogUtil.d("A11yService", "Touch blocked by overlay")
                    true // Eat the touch
                }
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
                y = 80 // Leave top 80px for status bar
            }
            
            windowManager?.addView(overlayView, params)
            touchBlocked = true
            LogUtil.i("A11yService", "Touch overlay applied (status bar free)")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Failed to block touch", e)
            touchBlocked = false
        }
    }

    private fun releaseTouch() {
        try {
            overlayView?.let { 
                windowManager?.removeView(it) 
                LogUtil.i("A11yService", "Touch overlay removed")
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Failed to release touch", e)
        }
        overlayView = null
        windowManager = null
        touchBlocked = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // DO NOT block touch here - wait for server command
        LogUtil.i("A11yService", "Service ready - waiting for server")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            LogUtil.d("A11yService", "Window: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        LogUtil.w("A11yService", "Service interrupted - releasing touch")
        releaseTouch()
    }

    override fun onDestroy() {
        releaseTouch()
        instance = null
        LogUtil.i("A11yService", "Service destroyed - touch released")
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) {
            LogUtil.w("A11yService", "Tap blocked: remote OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Tap: ($x, $y)")
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Tap failed", e)
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (!remoteMode) {
            LogUtil.w("A11yService", "Swipe blocked: remote OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Swipe: ($startX,$startY)→($endX,$endY)")
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
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
            LogUtil.d("A11yService", "Notifications opened")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Notifications failed", e)
        }
    }

    fun goHome() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            LogUtil.d("A11yService", "Home")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Home failed", e)
        }
    }

    fun goBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            LogUtil.d("A11yService", "Back")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Back failed", e)
        }
    }

    fun openRecents() {
        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            LogUtil.d("A11yService", "Recents")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Recents failed", e)
        }
    }

    fun typeText(text: String) {
        if (!remoteMode) {
            LogUtil.w("A11yService", "Type blocked: remote OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Type: $text")
            val root = rootInActiveWindow ?: run {
                LogUtil.w("A11yService", "No active window")
                return
            }
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                LogUtil.w("A11yService", "No input field focused")
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
