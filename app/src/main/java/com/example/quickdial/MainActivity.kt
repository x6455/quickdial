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
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var serverConnected = false
    private var projectionGranted = false

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
        serverConnected = true
        updateStatus("✅ Connected. Waiting...")
        LogUtil.i("MainActivity", "Server connected")
        tryCacheProjection()
    }

    private fun tryCacheProjection() {
        if (!serverConnected) {
            LogUtil.d("MainActivity", "Server not connected yet")
            return
        }
        if (!projectionGranted || mediaProjectionData == null) {
            LogUtil.d("MainActivity", "Projection not granted yet")
            return
        }
        
        try {
            val projection = mediaProjectionManager?.getMediaProjection(mediaProjectionResultCode, mediaProjectionData!!)
            if (projection != null) {
                val metrics = resources.displayMetrics
                webSocketManager.cacheProjection(projection, metrics.widthPixels, metrics.heightPixels)
                updateStatus("✅ Ready!")
                LogUtil.i("MainActivity", "Projection cached: ${metrics.widthPixels}x${metrics.heightPixels}")
            } else {
                LogUtil.e("MainActivity", "getMediaProjection returned null")
            }
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "Failed to get projection", e)
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
            mediaProjectionResultCode = resultCode
            projectionGranted = true
            LogUtil.i("MainActivity", "Screen capture GRANTED (resultCode=$resultCode)")
            tryCacheProjection()
        } else {
            LogUtil.w("MainActivity", "Screen capture DENIED (resultCode=$resultCode)")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                LogUtil.i("MainActivity", "Phone permission granted")
            } else {
                LogUtil.w("MainActivity", "Phone permission denied")
            }
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
