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
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class WebSocketManager(private val activity: MainActivity) {
    
    companion object {
        private const val TAG = "WSManager"
        private const val SERVER_URL = "ws://34.30.143.238:3010/?type=phone&password=admin123"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val MAX_RECONNECT_DELAY = 60000L // 60 seconds max
        private const val INITIAL_RECONNECT_DELAY = 1000L // 1 second
    }
    
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }
    
    private var webSocketClient: WebSocketClient? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var phoneNumber = "+1234567890"
    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectAttempts = 0
    private val shouldReconnect = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var screenCaptureActive = false
    
    fun connect() {
        shouldReconnect.set(true)
        reconnectDelay = INITIAL_RECONNECT_DELAY
        reconnectAttempts = 0
        doConnect()
    }
    
    private fun doConnect() {
        if (connectionState == ConnectionState.CONNECTED) return
        
        connectionState = ConnectionState.CONNECTING
        activity.updateStatus("Connecting to server...")
        
        try {
            webSocketClient = object : WebSocketClient(URI(SERVER_URL)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connectionState = ConnectionState.CONNECTED
                    reconnectAttempts = 0
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    Log.d(TAG, "✅ Connected to server")
                    activity.updateStatus("✅ Connected to server")
                    activity.onServerConnected()
                    startHeartbeat()
                }
                
                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Connection closed: $reason (code: $code, remote: $remote)")
                    handleDisconnect()
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error: ${ex?.message}")
                    handleDisconnect()
                }
            }
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create connection: ${e.message}")
            handleDisconnect()
        }
    }
    
    private fun handleMessage(message: String) {
        try {
            val cmd = org.json.JSONObject(message)
            val accessibilityService = QuickAccessibilityService.instance ?: return
            
            when (cmd.optString("action")) {
                "tap" -> {
                    accessibilityService.performTap(
                        cmd.getDouble("x").toFloat(),
                        cmd.getDouble("y").toFloat()
                    )
                }
                "swipe" -> {
                    accessibilityService.performSwipe(
                        cmd.getDouble("startX").toFloat(), cmd.getDouble("startY").toFloat(),
                        cmd.getDouble("endX").toFloat(), cmd.getDouble("endY").toFloat()
                    )
                }
                "type" -> accessibilityService.typeText(cmd.getString("text"))
                "home" -> accessibilityService.goHome()
                "back" -> accessibilityService.goBack()
                "recents" -> accessibilityService.openRecents()
                "notifications" -> accessibilityService.openNotifications()
                "call" -> accessibilityService.makeCall(phoneNumber, activity)
                "screenshot" -> captureFrame()
                "mode" -> {
                    val remote = cmd.optBoolean("remote", false)
                    if (remote) {
                        accessibilityService.enableRemoteMode()
                        activity.updateStatus("🔴 Remote mode ON")
                        startScreenCaptureIfReady()
                    } else {
                        accessibilityService.disableRemoteMode()
                        activity.updateStatus("🟢 Remote mode OFF")
                        stopScreenCapture()
                    }
                }
                "config" -> {
                    cmd.optString("phoneNumber")?.let { 
                        phoneNumber = it
                        Log.d(TAG, "Phone number updated: $phoneNumber")
                    }
                }
                "ping" -> {
                    // Respond to server ping
                    sendMessage("{\"type\":\"pong\"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }
    
    private fun handleDisconnect() {
        stopHeartbeat()
        stopScreenCapture()
        connectionState = ConnectionState.DISCONNECTED
        QuickAccessibilityService.instance?.disableRemoteMode()
        activity.updateStatus("Disconnected")
        
        if (shouldReconnect.get()) {
            scheduleReconnect()
        }
    }
    
    private fun scheduleReconnect() {
        connectionState = ConnectionState.RECONNECTING
        reconnectAttempts++
        val delay = min(reconnectDelay, MAX_RECONNECT_DELAY)
        
        activity.updateStatus("Reconnecting in ${delay/1000}s (attempt $reconnectAttempts)...")
        Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        
        mainHandler.postDelayed({
            if (shouldReconnect.get() && connectionState != ConnectionState.CONNECTED) {
                doConnect()
            }
        }, delay)
        
        // Exponential backoff
        reconnectDelay = min(reconnectDelay * 2, MAX_RECONNECT_DELAY)
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (connectionState == ConnectionState.CONNECTED) {
                    sendMessage("{\"type\":\"ping\"}")
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
    
    fun startScreenCapture(projection: MediaProjection, width: Int, height: Int) {
        stopScreenCapture()
        
        mediaProjection = projection
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            width, height,
            activity.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        
        imageReader!!.setOnImageAvailableListener({ reader ->
            captureFrame()
        }, mainHandler)
        
        screenCaptureActive = true
        Log.d(TAG, "Screen capture started")
    }
    
    private fun startScreenCaptureIfReady() {
        if (screenCaptureActive) return
        // Screen capture starts when MediaProjection is ready from MainActivity
    }
    
    private fun stopScreenCapture() {
        screenCaptureActive = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        Log.d(TAG, "Screen capture stopped")
    }
    
    private fun captureFrame() {
        if (!isConnected() || !screenCaptureActive) return
        if (!QuickAccessibilityService.instance?.isRemoteMode()!!) return
        
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            val width = image.width
            val height = image.height
            
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            
            // Compress and send
            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
            croppedBitmap.recycle()
            
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            baos.close()
            
            sendMessage("{\"type\":\"frame\",\"data\":\"$base64\"}")
            
            image.close()
        } catch (e: Exception) {
            Log.e(TAG, "Frame capture error: ${e.message}")
        }
    }
    
    private fun sendMessage(message: String) {
        try {
            if (webSocketClient?.isOpen == true) {
                webSocketClient?.send(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
        }
    }
    
    fun disconnect() {
        shouldReconnect.set(false)
        stopHeartbeat()
        stopScreenCapture()
        QuickAccessibilityService.instance?.disableRemoteMode()
        try {
            webSocketClient?.close()
        } catch (e: Exception) {}
        webSocketClient = null
        connectionState = ConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }
}
