package com.example.quickdial

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class WebSocketManager(private val activity: MainActivity) {

    companion object {
        private const val SERVER_URL = "ws://34.30.143.238:3010/?type=phone&password=admin123"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val MAX_RECONNECT_DELAY = 60000L
        private const val INITIAL_RECONNECT_DELAY = 1000L
        private const val FRAME_QUALITY = 40
        private const val MAX_FRAME_SKIP = 3
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private var webSocketClient: WebSocketClient? = null
    private var cachedProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var phoneNumber = "+1234567890"
    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectAttempts = 0
    private val shouldReconnect = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var streamingActive = false
    private var remoteModeActive = false
    private var streamingEnabled = false // Separate toggle for streaming
    private var frameSkipCount = 0

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
                "screenshot" -> captureSingleFrame()
                "mode" -> setRemoteMode(cmd.optBoolean("remote", false))
                "streaming" -> {
                    streamingEnabled = cmd.optBoolean("enabled", false)
                    if (streamingEnabled) {
                        activity.requestScreenCapture()
                    } else {
                        stopStreaming()
                    }
                }
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
            if (streamingEnabled) startStreaming()
            activity.updateStatus("🔴 Remote ON")
        } else {
            stopStreaming()
            a11y?.remoteMode = false
            a11y?.releaseTouch()
            activity.updateStatus("🟢 Remote OFF")
        }
    }

    fun cacheProjection(projection: MediaProjection?, width: Int, height: Int) {
        stopStreaming()
        cachedProjection = projection
        if (cachedProjection != null && streamingEnabled && remoteModeActive) {
            startStreaming()
        }
    }

    private fun startStreaming() {
    if (streamingActive || cachedProjection == null || !isConnected()) return
    try {
        val projection = cachedProjection!!
        val metrics = activity.resources.displayMetrics
        val scale = 0.5f  // 50% scale for faster streaming
        val w = (metrics.widthPixels * scale).toInt()
        val h = (metrics.heightPixels * scale).toInt()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay("ScreenCapture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!streamingActive || !isConnected()) { try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}; return@setOnImageAvailableListener }
            if (frameSkipCount < MAX_FRAME_SKIP) { frameSkipCount++; try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}; return@setOnImageAvailableListener }
            frameSkipCount = 0
            captureAndSend(reader)
        }, mainHandler)
        streamingActive = true
        LogUtil.i("WS", "Streaming: ${w}x${h}")
    } catch (e: Exception) { stopStreaming() }
}

    private fun stopStreaming() {
        streamingActive = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null
    }

    private fun captureAndSend(reader: ImageReader) {
        try {
            val image = reader.acquireLatestImage() ?: return
            val w = image.width; val h = image.height
            val buffer = image.planes[0].buffer
            val ps = image.planes[0].pixelStride; val rs = image.planes[0].rowStride
            val bitmap = Bitmap.createBitmap(w + (rs - ps * w) / ps, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer); image.close()
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, w, h); bitmap.recycle()
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos); cropped.recycle()
            sendRaw("{\"type\":\"frame\",\"data\":\"${Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)}\"}")
            baos.close()
        } catch (_: Exception) {}
    }

    private fun captureSingleFrame() { if (streamingActive && imageReader != null) try { captureAndSend(imageReader!!) } catch (_: Exception) {} }

    private fun handleDisconnect() {
        stopHeartbeat(); stopStreaming()
        QuickAccessibilityService.instance?.let { it.remoteMode = false; it.releaseTouch() }
        remoteModeActive = false
        connectionState = ConnectionState.DISCONNECTED
        activity.updateStatus("Disconnected")
        if (shouldReconnect.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        connectionState = ConnectionState.RECONNECTING; reconnectAttempts++
        val delay = min(reconnectDelay, MAX_RECONNECT_DELAY)
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

    fun disconnect() {
        shouldReconnect.set(false); stopHeartbeat(); stopStreaming()
        QuickAccessibilityService.instance?.let { it.remoteMode = false; it.releaseTouch() }
        cachedProjection = null
        try { webSocketClient?.close() } catch (_: Exception) {}
        connectionState = ConnectionState.DISCONNECTED
    }
}
