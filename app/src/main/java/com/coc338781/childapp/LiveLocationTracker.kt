package com.coc338781.childapp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object LiveLocationTracker {
    private const val TAG = "LiveLocationTracker"
    private const val PREF = "livetrack_prefs"
    private const val KEY_ACTIVE = "active"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_LAST_SENT = "last_sent"
    private const val INTERVAL_MS = 15 * 60 * 1000L // 15 min
    private const val DURATION_MS = 24 * 60 * 60 * 1000L // 24h

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    fun start(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val endTime = System.currentTimeMillis() + DURATION_MS
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_END_TIME, endTime)
                .putLong(KEY_LAST_SENT, 0L)
                .apply()

            startLocationUpdates(context)
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
        }
    }

    fun stop(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            stopLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    fun isActive(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val active = prefs.getBoolean(KEY_ACTIVE, false)
            val endTime = prefs.getLong(KEY_END_TIME, 0L)

            if (System.currentTimeMillis() > endTime) {
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
                return false
            }
            active
        } catch (_: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(context: Context) {
        try {
            if (locationListener != null) return

            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    checkAndSend(context, location)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            // GPS + Network දෙකම try කරන්න
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 60_000L, 0f, locationListener!!, Looper.getMainLooper()
                )
            } catch (_: Exception) {}

            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 60_000L, 0f, locationListener!!, Looper.getMainLooper()
                )
            } catch (_: Exception) {}

            Log.d(TAG, "Location updates started")

        } catch (e: Exception) {
            Log.e(TAG, "startLocationUpdates error", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
            locationListener = null
            locationManager = null
        } catch (_: Exception) {}
    }

    private fun checkAndSend(context: Context, location: Location) {
        try {
            if (!isActive(context)) {
                stopLocationUpdates()
                return
            }

            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val lastSent = prefs.getLong(KEY_LAST_SENT, 0L)
            val now = System.currentTimeMillis()

            if (now - lastSent < INTERVAL_MS) return

            prefs.edit().putLong(KEY_LAST_SENT, now).apply()

            val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val link = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            val msg = "📍 Live Location Update\n" +
                    "🕐 ${fmt.format(Date(now))}\n\n" +
                    "Lat: ${location.latitude}\n" +
                    "Lng: ${location.longitude}\n" +
                    "Accuracy: ${location.accuracy}m\n\n$link"

            sendTelegram(context, msg)
        } catch (e: Exception) {
            Log.e(TAG, "checkAndSend error", e)
        }
    }

    private fun sendTelegram(context: Context, msg: String) {
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
