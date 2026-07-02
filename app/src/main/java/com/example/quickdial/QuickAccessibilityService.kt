package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickAccessibilityService? = null
    }

    private var remoteMode = false

    fun enableRemoteMode() {
        remoteMode = true
        LogUtil.i("A11yService", "✅ Remote mode ENABLED")
    }

    fun disableRemoteMode() {
        remoteMode = false
        LogUtil.i("A11yService", "❌ Remote mode DISABLED")
    }

    fun isRemoteMode(): Boolean = remoteMode

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogUtil.i("A11yService", "Service connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only log window changes for debugging
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            LogUtil.d("A11yService", "Window changed: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        LogUtil.w("A11yService", "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        LogUtil.i("A11yService", "Service destroyed")
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) {
            LogUtil.w("A11yService", "Tap blocked: remote mode OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Tap at ($x, $y)")
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
            LogUtil.w("A11yService", "Swipe blocked: remote mode OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Swipe: ($startX,$startY) → ($endX,$endY)")
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
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Open notifications failed", e)
        }
    }

    fun goHome() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            LogUtil.d("A11yService", "Home pressed")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Home failed", e)
        }
    }

    fun goBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            LogUtil.d("A11yService", "Back pressed")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Back failed", e)
        }
    }

    fun openRecents() {
        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            LogUtil.d("A11yService", "Recents pressed")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Recents failed", e)
        }
    }

    fun typeText(text: String) {
        if (!remoteMode) {
            LogUtil.w("A11yService", "Type blocked: remote mode OFF")
            return
        }
        try {
            LogUtil.d("A11yService", "Typing: $text")
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
                LogUtil.d("A11yService", "Text set successfully")
            } else {
                LogUtil.w("A11yService", "No focused input field")
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Type text failed", e)
        }
    }

    fun makeCall(number: String, context: Context) {
        try {
            val encodedNumber = Uri.encode(number)
            LogUtil.i("A11yService", "Calling: $number → $encodedNumber")
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Call failed", e)
        }
    }

    fun tapByText(text: String): Boolean {
        if (!remoteMode) return false
        try {
            val root = rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    LogUtil.d("A11yService", "Tapped by text: $text")
                    return true
                }
            }
            LogUtil.w("A11yService", "No clickable element found: $text")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Tap by text failed", e)
        }
        return false
    }
}
