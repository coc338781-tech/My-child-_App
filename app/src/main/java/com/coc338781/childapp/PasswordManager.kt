package com.coc338781.childapp

import android.content.Context

object PasswordManager {
    private const val PREF = "app_prefs"
    private const val KEY_PASSWORD = "app_password"
    private const val DEFAULT_PASSWORD = "Hasa@9244"

    fun getPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
    }

    fun setPassword(context: Context, newPassword: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PASSWORD, newPassword).apply()
    }

    fun checkPassword(context: Context, input: String): Boolean {
        return input == getPassword(context)
    }
}
