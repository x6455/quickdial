package com.example.quickdial

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtil {
    private var logSender: ((String) -> Unit)? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setSender(sender: (String) -> Unit) {
        logSender = sender
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        sendToServer("DEBUG", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else message
        sendToServer("ERROR", tag, fullMessage)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        sendToServer("WARN", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        sendToServer("INFO", tag, message)
    }

    private fun sendToServer(level: String, tag: String, message: String) {
        try {
            val json = JSONObject().apply {
                put("type", "log")
                put("level", level)
                put("tag", tag)
                put("message", message)
                put("timestamp", dateFormat.format(Date()))
            }
            logSender?.invoke(json.toString())
        } catch (e: Exception) {
            Log.e("LogUtil", "Failed to send log: ${e.message}")
        }
    }
}
