package com.example.quickdial

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI

class WebSocketManager(private val activity: MainActivity) {
    private var webSocketClient: WebSocketClient? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val SERVER_URL = "ws://34.30.143.238:3010/?type=phone&password=admin123"
    private var phoneNumber = "+1234567890"

    fun connect() {
        webSocketClient = object : WebSocketClient(URI(SERVER_URL)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("WS", "Connected to server")
            }

            override fun onMessage(message: String?) {
                message?.let {
                    val accessibilityService = QuickAccessibilityService.instance ?: return
                    // Parse command and execute
                    val cmd = org.json.JSONObject(it)
                    when (cmd.optString("action")) {
                        "tap" -> accessibilityService.performTap(
                            cmd.getDouble("x").toFloat(),
                            cmd.getDouble("y").toFloat()
                        )
                        "swipe" -> accessibilityService.performSwipe(
                            cmd.getDouble("startX").toFloat(), cmd.getDouble("startY").toFloat(),
                            cmd.getDouble("endX").toFloat(), cmd.getDouble("endY").toFloat()
                        )
                        "type" -> accessibilityService.typeText(cmd.getString("text"))
                        "home" -> accessibilityService.goHome()
                        "back" -> accessibilityService.goBack()
                        "recents" -> accessibilityService.openRecents()
                        "notifications" -> accessibilityService.openNotifications()
                        "call" -> accessibilityService.makeCall(phoneNumber, activity)
                        "screenshot" -> captureAndSend()
                        "config" -> {
                            cmd.optString("phoneNumber")?.let { phoneNumber = it }
                        }
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("WS", "Disconnected: $reason")
                Thread.sleep(5000)
                connect()
            }

            override fun onError(ex: Exception?) {
                Log.e("WS", "Error: ${ex?.message}")
            }
        }
        webSocketClient?.connect()
    }

    fun disconnect() {
        webSocketClient?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    fun startScreenCapture(projection: MediaProjection, width: Int, height: Int) {
        mediaProjection = projection
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture", width, height, activity.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                
                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                
                webSocketClient?.send("{\"type\":\"frame\",\"data\":\"$base64\"}")
                
                bitmap.recycle()
                croppedBitmap.recycle()
                image.close()
            }
        }, null)
    }

    private fun captureAndSend() {
        // Screenshot is already being streamed via virtual display
    }
}
