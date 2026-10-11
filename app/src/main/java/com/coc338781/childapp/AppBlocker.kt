package com.coc338781.childapp

import android.content.Context
import android.content.pm.PackageManager

object AppBlocker {
    private const val PREF = "blocker_prefs"
    private const val KEY_BLOCKED = "blocked_apps"

    fun blockApp(context: Context, pkg: String) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_BLOCKED, mutableSetOf()) ?: mutableSetOf()
            val newSet = existing.toMutableSet()
            newSet.add(pkg)
            prefs.edit().putStringSet(KEY_BLOCKED, newSet).apply()
        } catch (_: Exception) {}
    }

    fun unblockApp(context: Context, pkg: String) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_BLOCKED, mutableSetOf()) ?: mutableSetOf()
            val newSet = existing.toMutableSet()
            newSet.remove(pkg)
            prefs.edit().putStringSet(KEY_BLOCKED, newSet).apply()
        } catch (_: Exception) {}
    }

    fun isBlocked(context: Context, pkg: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_BLOCKED, mutableSetOf()) ?: mutableSetOf()
            existing.contains(pkg)
        } catch (_: Exception) { false }
    }

    fun getBlockedList(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_BLOCKED, mutableSetOf()) ?: mutableSetOf()
            existing.toList()
        } catch (_: Exception) { emptyList() }
    }

    fun clearAll(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_BLOCKED, mutableSetOf()).apply()
        } catch (_: Exception) {}
    }

    // App name එකෙන් package name එක හොයාගන්න
    fun findPackageByName(context: Context, name: String): String? {
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.equals(name, ignoreCase = true) ||
                    label.contains(name, ignoreCase = true)) {
                    return app.packageName
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
