package com.coc338781.childapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream

object CameraHelper {
    private const val TAG = "CameraHelper"
    private const val MAX_DIM = 1600
    private const val JPEG_QUALITY = 75

    fun listCameras(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sb = StringBuilder()
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                sb.append("ID=$id facing=$facingName flash=$flash\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun takePhoto(context: Context, backCamera: Boolean, onResult: (File?, String) -> Unit) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val candidates = findCameras(manager, backCamera)

            if (candidates.isEmpty()) {
                onResult(null, "No ${if (backCamera) "back" else "front"} camera found. Available:\n" + listCameras(context))
                return
            }

            tryCamera(context, manager, candidates, 0, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "err", e)
            onResult(null, "Exception: ${e.message}")
        }
    }

    private fun tryCamera(
        context: Context,
        manager: CameraManager,
        candidates: List<String>,
        index: Int,
        onResult: (File?, String) -> Unit
    ) {
        if (index >= candidates.size) {
            onResult(null, "All cameras failed (tried ${candidates.size} IDs)")
            return
        }

        val cameraId = candidates[index]
        Log.d(TAG, "Trying camera ID=$cameraId (${index + 1}/${candidates.size})")

        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // 32MP නොගන්න — max 1920x1080 (memory save)
val maxAllowed = 1920
val size = map?.getOutputSizes(ImageFormat.JPEG)
    ?.filter { it.width <= maxAllowed && it.height <= maxAllowed }
    ?.maxByOrNull { it.width * it.height }
    ?: map?.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { it.width * it.height }
    ?: Size(1280, 720)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            val thread = HandlerThread("Cam_$cameraId").apply { start() }
            val handler = Handler(thread.looper)
            var device: CameraDevice? = null
            var finished = false

            reader.setOnImageAvailableListener({ r ->
                if (finished) return@setOnImageAvailableListener
                finished = true
                val img = r.acquireLatestImage()
                if (img == null) {
                    try { device?.close() } catch (_: Exception) {}
                    try { r.close() } catch (_: Exception) {}
                    thread.quitSafely()
                    tryCamera(context, manager, candidates, index + 1, onResult)
                    return@setOnImageAvailableListener
                }
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val rawSize = bytes.size / 1024

                    // ==== COMPRESS ====
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val maxDim = MAX_DIM
                    val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    } else 1f
                    val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                    } else bitmap

                    val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use {
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                    }
                    if (scaled != bitmap) scaled.recycle()
                    bitmap.recycle()

                    val finalSize = file.length() / 1024

                    img.close()
                    device?.close()
                    r.close()
                    thread.quitSafely()
                    onResult(file, "OK ID=$cameraId (${rawSize}KB→${finalSize}KB)")
                } catch (e: Exception) {
                    img.close()
                    try { device?.close() } catch (_: Exception) {}
                    try { r.close() } catch (_: Exception) {}
                    thread.quitSafely()
                    tryCamera(context, manager, candidates, index + 1, onResult)
                }
            }, handler)

            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(reader.surface)
                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        if (!finished) {
                                            finished = true
                                            try { camera.close() } catch (_: Exception) {}
                                            thread.quitSafely()
                                            tryCamera(context, manager, candidates, index + 1, onResult)
                                        }
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (!finished) {
                                        finished = true
                                        try { camera.close() } catch (_: Exception) {}
                                        thread.quitSafely()
                                        tryCamera(context, manager, candidates, index + 1, onResult)
                                    }
                                }
                            }, handler)
                    } catch (e: Exception) {
                        if (!finished) {
                            finished = true
                            try { camera.close() } catch (_: Exception) {}
                            thread.quitSafely()
                            tryCamera(context, manager, candidates, index + 1, onResult)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (!finished) {
                        finished = true
                        thread.quitSafely()
                        tryCamera(context, manager, candidates, index + 1, onResult)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (!finished) {
                        finished = true
                        thread.quitSafely()
                        tryCamera(context, manager, candidates, index + 1, onResult)
                    }
                }
            }

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onResult(null, "No CAMERA permission")
                return
            }
            manager.openCamera(cameraId, cb, handler)
        } catch (e: Exception) {
            tryCamera(context, manager, candidates, index + 1, onResult)
        }
    }

    private fun findCameras(manager: CameraManager, back: Boolean): List<String> {
        val target = if (back) CameraCharacteristics.LENS_FACING_BACK
                     else CameraCharacteristics.LENS_FACING_FRONT
        val result = mutableListOf<String>()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == target) {
                result.add(id)
            }
        }
        return result
    }
}
