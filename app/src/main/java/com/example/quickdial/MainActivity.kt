package com.example.quickdial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var serverReady = false
    private var projectionReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webSocketManager = WebSocketManager(this)
        
        updateStatus("Starting QuickDial...")
        
        if (!isAccessibilityServiceEnabled()) {
            updateStatus("⚠ Enable Accessibility Service first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        
        requestPhonePermission()
        requestScreenCapture()
        webSocketManager.connect()
    }

    fun updateStatus(text: String) {
        runOnUiThread {
            try {
                findViewById<TextView>(R.id.statusText).text = text
            } catch (e: Exception) {}
        }
    }

    fun onServerConnected() {
        serverReady = true
        updateStatus("✅ Connected. Waiting for remote mode...")
        tryStartCapture()
    }

    private fun tryStartCapture() {
        if (serverReady && projectionReady) {
            val intent = mediaProjectionData ?: return
            val projection = mediaProjectionManager?.getMediaProjection(RESULT_OK, intent)
            projection?.let { proj ->
                val metrics = resources.displayMetrics
                webSocketManager.startScreenCapture(proj, metrics.widthPixels, metrics.heightPixels)
                updateStatus("✅ Ready for remote control")
            }
        }
    }

    private fun requestPhonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun requestScreenCapture() {
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager!!.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQUEST
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            mediaProjectionData = data
            projectionReady = true
            tryStartCapture()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${QuickAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    override fun onDestroy() {
        webSocketManager.disconnect()
        super.onDestroy()
    }
}
