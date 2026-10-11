package com.coc338781.childapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "UpdateManager"

    // APK download කරලා install trigger කරනවා
    suspend fun downloadAndInstall(context: Context, url: String, onStatus: (String) -> Unit) {
        try {
            onStatus("⬇️ Downloading APK...")

            // Download folder එකට save කරන්න
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val success = downloadFile(url, apkFile)

            if (!success || !apkFile.exists() || apkFile.length() < 1000) {
                onStatus("❌ Download failed")
                return
            }

            val sizeKB = apkFile.length() / 1024
            onStatus("✅ Downloaded ($sizeKB KB)\n📦 Installing...")

            // Install trigger කරන්න
            installApk(context, apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            onStatus("❌ Error: ${e.message}")
        }
    }

    // URL එකෙන් file එක download කරනවා
    private suspend fun downloadFile(urlStr: String, dest: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    Log.e(TAG, "HTTP ${conn.responseCode}")
                    return@withContext false
                }

                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(8192)
                        var len: Int
                        var total = 0L
                        while (input.read(buf).also { len = it } > 0) {
                            output.write(buf, 0, len)
                            total += len
                        }
                        Log.d(TAG, "Downloaded: $total bytes")
                    }
                }
                conn.disconnect()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                false
            }
        }
    }

    // APK install intent trigger කරන්න
    private fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            context.startActivity(intent)
            Log.d(TAG, "Install intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
        }
    }
}
