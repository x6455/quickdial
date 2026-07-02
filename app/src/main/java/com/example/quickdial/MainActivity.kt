package com.example.quickdial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val PERMISSION_REQUEST_CODE = 100
    private val SCREEN_CAPTURE_REQUEST = 101
    private lateinit var webSocketManager: WebSocketManager
    private var mediaProjectionData: Intent? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var serverConnected = false
    private var projectionGranted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceStarted = false
    private var waitingForProjection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webSocketManager = WebSocketManager(this)
        updateStatus("Starting QuickDial...")
        if (!isAccessibilityServiceEnabled()) {
            updateStatus("⚠ Enable Accessibility in Settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        requestPhonePermission()
        // DO NOT request screen capture here - only when streaming toggled
        webSocketManager.connect()
    }

    fun updateStatus(text: String) {
        runOnUiThread { try { findViewById<TextView>(R.id.statusText).text = text } catch (_: Exception) {} }
    }

    fun onServerConnected() {
        serverConnected = true
        updateStatus("✅ Connected")
        tryCacheProjection()
    }

    fun requestScreenCapture() {
        if (waitingForProjection) return
        waitingForProjection = true
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager!!.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
    }

    private fun tryCacheProjection() {
        if (!serverConnected || !projectionGranted || mediaProjectionData == null) return
        if (!serviceStarted) {
            val serviceIntent = Intent(this, MediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            serviceStarted = true
            mainHandler.postDelayed({ getProjection() }, 1500)
        } else getProjection()
    }

    private fun getProjection() {
        try {
            val projection = mediaProjectionManager?.getMediaProjection(mediaProjectionResultCode, mediaProjectionData!!)
            if (projection != null) {
                val metrics = resources.displayMetrics
                webSocketManager.cacheProjection(projection, metrics.widthPixels, metrics.heightPixels)
                updateStatus("✅ Ready!")
            } else {
                mainHandler.postDelayed({ getProjection() }, 2000)
            }
        } catch (e: SecurityException) {
            mainHandler.postDelayed({ getProjection() }, 2000)
        } catch (e: Exception) {}
    }

    private fun requestPhonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), PERMISSION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        waitingForProjection = false
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            mediaProjectionData = data; mediaProjectionResultCode = resultCode; projectionGranted = true
            tryCacheProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${QuickAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(service)
    }

    override fun onDestroy() {
        webSocketManager.disconnect()
        stopService(Intent(this, MediaProjectionService::class.java))
        super.onDestroy()
    }
}
