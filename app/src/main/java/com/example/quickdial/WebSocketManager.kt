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
        private const val MAX_FRAME_SKIP = 3 // Skip frames if sending too fast
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

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
    private var frameSkipCount = 0

    init {
        LogUtil.setSender { logJson -> sendRaw(logJson) }
    }

    fun connect() {
        shouldReconnect.set(true)
        reconnectDelay = INITIAL_RECONNECT_DELAY
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        if (connectionState == ConnectionState.CONNECTED) return

        connectionState = ConnectionState.CONNECTING
        LogUtil.i("WS", "Connecting to server...")
        activity.updateStatus("Connecting...")

        try {
            webSocketClient = object : WebSocketClient(URI(SERVER_URL)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connectionState = ConnectionState.CONNECTED
                    reconnectAttempts = 0
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    LogUtil.i("WS", "✅ Connected to server")
                    activity.updateStatus("✅ Connected")
                    activity.onServerConnected()
                    startHeartbeat()
                }

                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    LogUtil.w("WS", "Closed: $reason (code=$code, remote=$remote)")
                    handleDisconnect()
                }

                override fun onError(ex: Exception?) {
                    LogUtil.e("WS", "Error: ${ex?.message}", ex)
                    handleDisconnect()
                }
            }
            webSocketClient?.connect()
        } catch (e: Exception) {
            LogUtil.e("WS", "Connection failed", e)
            handleDisconnect()
        }
    }

    // Replace the handleMessage function:
private fun handleMessage(message: String) {
    try {
        val cmd = org.json.JSONObject(message)
        val a11y = QuickAccessibilityService.instance

        when (cmd.optString("action", cmd.optString("type"))) {
            "tap" -> a11y?.performTap(
                cmd.getDouble("x").toFloat(),
                cmd.getDouble("y").toFloat()
            )
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
            "screenshot" -> captureSingleFrame()
            "mode" -> {
                val remote = cmd.optBoolean("remote", false)
                setRemoteMode(remote)
            }
            "config" -> {
                cmd.optString("phoneNumber")?.let {
                    phoneNumber = it
                    LogUtil.i("WS", "Phone number: $phoneNumber")
                }
                LogUtil.d("WS", "Config received - waiting for mode toggle")
            }
            "ping" -> sendRaw("{\"type\":\"pong\"}")
        }
    } catch (e: Exception) {
        LogUtil.e("WS", "Message error", e)
    }
}

// Replace setRemoteMode:
fun setRemoteMode(remote: Boolean) {
    remoteModeActive = remote
    val a11y = QuickAccessibilityService.instance
    if (remote) {
        a11y?.enableRemoteMode()
        startStreaming()
        activity.updateStatus("Remote ON")
        LogUtil.i("WS", "Remote ON - touch blocked")
    } else {
        stopStreaming()
        a11y?.disableRemoteMode()
        activity.updateStatus("Remote OFF")
        LogUtil.i("WS", "Remote OFF - phone free")
    }
}
    

    fun cacheProjection(projection: MediaProjection?, width: Int, height: Int) {
        stopStreaming()
        cachedProjection = projection
        if (cachedProjection != null) {
            LogUtil.i("WS", "Projection cached: ${width}x${height}")
            // Don't start streaming yet - wait for remote mode ON
        }
    }

    private fun startStreaming() {
        if (streamingActive) return
        if (cachedProjection == null) {
            LogUtil.w("WS", "Cannot stream: no projection cached")
            return
        }
        if (!isConnected()) {
            LogUtil.w("WS", "Cannot stream: not connected")
            return
        }

        try {
            val projection = cachedProjection!!
            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, null
            )

            imageReader!!.setOnImageAvailableListener({ reader ->
                if (!streamingActive || !isConnected()) {
                    // Close image but don't process
                    try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }

                // Frame skipping to avoid overload
                if (frameSkipCount < MAX_FRAME_SKIP) {
                    frameSkipCount++
                    try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }
                frameSkipCount = 0

                captureAndSend(reader)
            }, mainHandler)

            streamingActive = true
            LogUtil.i("WS", "Streaming started: ${width}x${height}")
        } catch (e: Exception) {
            LogUtil.e("WS", "Failed to start streaming", e)
            stopStreaming()
        }
    }

    private fun stopStreaming() {
        streamingActive = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
        } catch (e: Exception) {
            LogUtil.e("WS", "Error stopping stream", e)
        }
        virtualDisplay = null
        imageReader = null
        LogUtil.i("WS", "Streaming stopped")
    }

    private fun captureAndSend(reader: ImageReader) {
        try {
            val image = reader.acquireLatestImage() ?: return
            val width = image.width
            val height = image.height

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()

            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos)
            croppedBitmap.recycle()

            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            baos.close()

            sendRaw("{\"type\":\"frame\",\"data\":\"$base64\"}")
        } catch (e: Exception) {
            LogUtil.e("WS", "Frame capture error", e)
        }
    }

    private fun captureSingleFrame() {
        if (!streamingActive || imageReader == null) return
        try {
            captureAndSend(imageReader!!)
        } catch (e: Exception) {
            LogUtil.e("WS", "Screenshot error", e)
        }
    }

    

// Replace handleDisconnect:
private fun handleDisconnect() {
    stopHeartbeat()
    stopStreaming()
    remoteModeActive = false
    QuickAccessibilityService.instance?.disableRemoteMode() // Release touch on disconnect
    connectionState = ConnectionState.DISCONNECTED
    activity.updateStatus("Disconnected - Phone free")
    
    if (shouldReconnect.get()) {
        scheduleReconnect()
    }
}

    private fun scheduleReconnect() {
        connectionState = ConnectionState.RECONNECTING
        reconnectAttempts++
        val delay = min(reconnectDelay, MAX_RECONNECT_DELAY)

        LogUtil.w("WS", "Reconnecting in ${delay/1000}s (attempt $reconnectAttempts)")
        activity.updateStatus("Reconnecting in ${delay/1000}s...")

        mainHandler.postDelayed({
            if (shouldReconnect.get() && connectionState != ConnectionState.CONNECTED) {
                doConnect()
            }
        }, delay)

        reconnectDelay = min(reconnectDelay * 2, MAX_RECONNECT_DELAY)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (connectionState == ConnectionState.CONNECTED) {
                    sendRaw("{\"type\":\"ping\"}")
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL)
                }
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED

    private fun sendRaw(message: String) {
        try {
            if (webSocketClient?.isOpen == true) {
                webSocketClient?.send(message)
            }
        } catch (e: Exception) {
            LogUtil.e("WS", "Send failed", e)
        }
    }

    fun disconnect() {
        LogUtil.i("WS", "Disconnecting...")
        shouldReconnect.set(false)
        stopHeartbeat()
        stopStreaming()
        remoteModeActive = false
        QuickAccessibilityService.instance?.disableRemoteMode()
        cachedProjection = null
        try { webSocketClient?.close() } catch (_: Exception) {}
        webSocketClient = null
        connectionState = ConnectionState.DISCONNECTED
    }
}
