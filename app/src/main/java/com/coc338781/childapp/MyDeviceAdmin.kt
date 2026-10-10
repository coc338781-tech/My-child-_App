package com.coc338781.childapp

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("MyDeviceAdmin", "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("MyDeviceAdmin", "Device Admin Disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "මේ ඇප් එකේ protection අයින් කරන්න බෑ. දෙමාපියන් අමතන්න."
    }
}
