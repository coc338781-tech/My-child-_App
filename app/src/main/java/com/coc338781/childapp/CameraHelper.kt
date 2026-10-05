package com.coc338781.childapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

object CameraHelper {
    private const val TAG = "CameraHelper"

    fun takePhoto(context: Context, backCamera: Boolean, onResult: (File?) -> Unit) {
        val owner = context as? LifecycleOwner ?: run {
            onResult(null); return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (backCamera) CameraSelector.LENS_FACING_BACK
                        else CameraSelector.LENS_FACING_FRONT
                    ).build()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, imageCapture)

                val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            onResult(file)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Photo error", exception)
                            onResult(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                onResult(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun recordVideo(context: Context, backCamera: Boolean, durationSec: Int, onResult: (File?) -> Unit) {
        val owner = context as? LifecycleOwner ?: run {
            onResult(null); return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (backCamera) CameraSelector.LENS_FACING_BACK
                        else CameraSelector.LENS_FACING_FRONT
                    ).build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, videoCapture)

                val file = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
                val options = FileOutputOptions.Builder(file).build()
                val pending = recorder.prepareRecording(context, options)
                val recording = pending.start(ContextCompat.getMainExecutor(context)) { }

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        recording.stop()
                        onResult(file)
                    } catch (e: Exception) {
                        Log.e(TAG, "Stop error", e)
                        onResult(null)
                    }
                }, durationSec * 1000L)

            } catch (e: Exception) {
                Log.e(TAG, "Video error", e)
                onResult(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
