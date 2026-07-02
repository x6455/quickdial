package com.example.quickdial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val PHONE_NUMBER = "+1234567890"
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        findViewById<TextView>(R.id.statusText).text = "Setting up..."
        
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // Check accessibility service
        if (!isAccessibilityServiceEnabled()) {
            findViewById<TextView>(R.id.statusText).text = "Enable Accessibility Service"
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        
        // Check phone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                PERMISSION_REQUEST_CODE
            )
        } else if (isAccessibilityServiceEnabled()) {
            makeCall()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled() && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            makeCall()
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
                if (isAccessibilityServiceEnabled()) {
                    makeCall()
                }
            } else {
                findViewById<TextView>(R.id.statusText).text = "Permission denied"
                finish()
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

    private fun makeCall() {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$PHONE_NUMBER"))
        startActivity(intent)
        finish()
    }
}
