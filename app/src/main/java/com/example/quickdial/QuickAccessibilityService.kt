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

    private var overlayView: android.view.View? = null
    private var windowManager: android.view.WindowManager? = null
    private var touchBlocked = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    var remoteMode = false

    fun blockTouch() {
        LogUtil.i("A11yService", "blockTouch() CALLED - touchBlocked=$touchBlocked remoteMode=$remoteMode instance=${instance != null}")
        
        if (touchBlocked) {
            LogUtil.w("A11yService", "blockTouch: Already blocked, skipping")
            return
        }
        
        if (instance == null) {
            LogUtil.e("A11yService", "blockTouch: instance is NULL, cannot proceed")
            return
        }
        
        mainHandler.post {
            LogUtil.i("A11yService", "blockTouch: Running on main thread (thread=${Thread.currentThread().name})")
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                LogUtil.i("A11yService", "blockTouch: WindowManager obtained")
                
                overlayView = android.view.View(this).apply {
                    setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                    setOnTouchListener { _, _ -> 
                        LogUtil.d("A11yService", "Touch EATEN by overlay")
                        true 
                    }
                }
                LogUtil.i("A11yService", "blockTouch: Overlay view created")
                
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
                LogUtil.i("A11yService", "blockTouch: LayoutParams created, calling addView...")
                
                windowManager?.addView(overlayView, params)
                touchBlocked = true
                LogUtil.i("A11yService", "✅✅✅ Touch BLOCKED successfully! touchBlocked=$touchBlocked")
                
            } catch (e: Exception) {
                LogUtil.e("A11yService", "❌ blockTouch FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                touchBlocked = false
            }
        }
        LogUtil.i("A11yService", "blockTouch: Posted to main handler")
    }

    fun releaseTouch() {
        LogUtil.i("A11yService", "releaseTouch() CALLED - touchBlocked=$touchBlocked")
        
        if (!touchBlocked) {
            LogUtil.d("A11yService", "releaseTouch: Not blocked, skipping")
            return
        }
        
        mainHandler.post {
            LogUtil.i("A11yService", "releaseTouch: Running on main thread")
            try {
                overlayView?.let { 
                    windowManager?.removeView(it)
                    LogUtil.i("A11yService", "releaseTouch: View removed from WindowManager")
                } ?: LogUtil.w("A11yService", "releaseTouch: overlayView was null")
            } catch (e: Exception) {
                LogUtil.e("A11yService", "releaseTouch FAILED", e)
            }
            overlayView = null
            windowManager = null
            touchBlocked = false
            LogUtil.i("A11yService", "✅ Touch RELEASED - touchBlocked=$touchBlocked")
        }
    }

    fun isTouchBlocked(): Boolean = touchBlocked

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        touchBlocked = false
        remoteMode = false
        overlayView = null
        windowManager = null
        LogUtil.i("A11yService", "Service ready - waiting for server control")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            LogUtil.d("A11yService", "Window: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        LogUtil.w("A11yService", "Interrupted - force releasing")
        remoteMode = false
        mainHandler.post { 
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            windowManager = null
            touchBlocked = false
        }
    }

    override fun onDestroy() {
        LogUtil.i("A11yService", "onDestroy - releasing touch")
        remoteMode = false
        mainHandler.post {
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            windowManager = null
            touchBlocked = false
        }
        instance = null
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) { LogUtil.w("A11yService", "Tap denied: remote OFF"); return }
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            LogUtil.d("A11yService", "Tap: $x,$y")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Tap failed", e)
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (!remoteMode) { LogUtil.w("A11yService", "Swipe denied: remote OFF"); return }
        try {
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
            LogUtil.d("A11yService", "Swipe: $startX,$startY→$endX,$endY")
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
            LogUtil.e("A11yService", "Notify failed", e)
        }
    }

    fun goHome() { try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {} }
    fun goBack() { try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {} }
    fun openRecents() { try { performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Exception) {} }

    fun typeText(text: String) {
        if (!remoteMode) { LogUtil.w("A11yService", "Type denied: remote OFF"); return }
        try {
            val root = rootInActiveWindow ?: return
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                LogUtil.d("A11yService", "Typed: $text")
            }
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Type failed", e)
        }
    }

    fun makeCall(number: String, context: Context) {
        try {
            val encodedNumber = Uri.encode(number)
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LogUtil.i("A11yService", "Calling: $number")
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Call failed", e)
        }
    }
}
