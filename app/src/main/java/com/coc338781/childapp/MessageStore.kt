package com.coc338781.childapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MessageStore {
    private const val PREF = "msg_store"
    private const val KEY_WHATSAPP = "wa_msgs"
    private const val KEY_FB = "fb_msgs"
    private const val KEY_IMO = "imo_msgs"
    private const val MAX_MESSAGES = 50

    data class Msg(val from: String, val text: String, val time: Long)

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
}
