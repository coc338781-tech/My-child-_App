package com.coc338781.childapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object CameraHelper {
    private const val TAG = "CameraHelper"
    private const val MAX_DIM = 2560
    private const val JPEG_QUALITY = 92
    private const val FOCUS_WAIT_MS = 1500L
    private const val EXPOSURE_WAIT_MS = 500L

    private val cameraBusy = AtomicBoolean(false)

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
        cameraBusy.set(false)

        if (!cameraBusy.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed({
                takePhoto(context, backCamera, onResult)
            }, 5000)
            return
        }

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val candidates = findCameras(manager, backCamera)

            if (candidates.isEmpty()) {
                cameraBusy.set(false)
                onResult(null, "No ${if (backCamera) "back" else "front"} camera")
                return
            }

            tryCamera(context, manager, candidates, 0, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "err", e)
            cameraBusy.set(false)
            onResult(null, "Exception: ${e.message}")
        }
    }

    fun takePhotoWithRetry(context: Context, backCamera: Boolean, onResult: (File?, String) -> Unit) {
        takePhoto(context, backCamera) { file, info ->
            if (file != null) {
                onResult(file, info)
            } else {
                Log.d(TAG, "First try failed: $info. Waiting 10s...")
                Handler(Looper.getMainLooper()).postDelayed({
                    takePhoto(context, backCamera) { f2, info2 ->
                        if (f2 != null) onResult(f2, "$info2 (retry)")
                        else onResult(null, "$info | retry failed: $info2")
                    }
                }, 10000)
            }
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
            cameraBusy.set(false)
            onResult(null, "All cameras failed")
            return
        }

        val cameraId = candidates[index]
        Log.d(TAG, "Trying camera ID=$cameraId")

        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // **Quality setting** — max 2560px
            val maxAllowed = MAX_DIM
            val size = map?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { it.width <= maxAllowed && it.height <= maxAllowed }
                ?.maxByOrNull { it.width * it.height }
                ?: map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)

            Log.d(TAG, "Photo size: ${size.width}x${size.height}")

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            val thread = HandlerThread("Cam_$cameraId").apply { start() }
            val handler = Handler(thread.looper)
            var device: CameraDevice? = null
            var finished = false

            val cleanup = {
                try { device?.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                try { thread.quitSafely() } catch (_: Exception) {}
                cameraBusy.set(false)
            }

            reader.setOnImageAvailableListener({ r ->
                if (finished) return@setOnImageAvailableListener
                finished = true
                val img = r.acquireLatestImage()
                if (img == null) {
                    cleanup()
                    tryCamera(context, manager, candidates, index + 1, onResult)
                    return@setOnImageAvailableListener
                }
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val rawSize = bytes.size / 1024

                    // **High Quality** — inSampleSize = 1 (no downsampling)
                    val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                    // Resize only if too big (>2560)
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
                        // **High quality JPEG = 92%**
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                    }
                    if (scaled != bitmap) scaled.recycle()
                    bitmap.recycle()

                    val finalSize = file.length() / 1024
                    img.close()
                    cleanup()
                    onResult(file, "OK ID=$cameraId (${bitmap.width}x${bitmap.height}, ${rawSize}KB→${finalSize}KB)")
                } catch (e: Exception) {
                    try { img.close() } catch (_: Exception) {}
                    cleanup()
                    tryCamera(context, manager, candidates, index + 1, onResult)
                }
            }, handler)

            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        // **Create session first** — for autofocus + auto-exposure
                        val sessionReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        sessionReq.addTarget(reader.surface)

                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        // 1. Start preview session — for focus
                                        session.setRepeatingRequest(sessionReq.build(), null, handler)

                                        // 2. Wait for autofocus + auto-exposure (1.5s)
                                        handler.postDelayed({
                                            try {
                                                // 3. Now capture photo with autofocus trigger
                                                val captureReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                                captureReq.addTarget(reader.surface)

                                                // **Autofocus trigger**
                                                captureReq.set(
                                                    CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                                )
                                                captureReq.set(
                                                    CaptureRequest.CONTROL_AF_TRIGGER,
                                                    CaptureRequest.CONTROL_AF_TRIGGER_START
                                                )

                                                // **Auto exposure**
                                                captureReq.set(
                                                    CaptureRequest.CONTROL_AE_MODE,
                                                    CaptureRequest.CONTROL_AE_MODE_ON
                                                )

                                                // **Auto white balance**
                                                captureReq.set(
                                                    CaptureRequest.CONTROL_AWB_MODE,
                                                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                                                )

                                                // **Anti-shake (if supported)**
                                                try {
                                                    captureReq.set(
                                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                                    )
                                                } catch (_: Exception) {}

                                                session.capture(captureReq.build(), null, handler)

                                                // Reset AF trigger
                                                val resetReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                                resetReq.addTarget(reader.surface)
                                                resetReq.set(
                                                    CaptureRequest.CONTROL_AF_TRIGGER,
                                                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                                                )
                                                session.setRepeatingRequest(resetReq.build(), null, handler)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Capture error", e)
                                                if (!finished) {
                                                    finished = true
                                                    cleanup()
                                                    tryCamera(context, manager, candidates, index + 1, onResult)
                                                }
                                            }
                                        }, FOCUS_WAIT_MS)

                                    } catch (e: Exception) {
                                        if (!finished) {
                                            finished = true
                                            cleanup()
                                            tryCamera(context, manager, candidates, index + 1, onResult)
                                        }
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (!finished) {
                                        finished = true
                                        cleanup()
                                        tryCamera(context, manager, candidates, index + 1, onResult)
                                    }
                                }
                            }, handler)
                    } catch (e: Exception) {
                        if (!finished) {
                            finished = true
                            cleanup()
                            tryCamera(context, manager, candidates, index + 1, onResult)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    if (!finished) {
                        finished = true
                        cleanup()
                        tryCamera(context, manager, candidates, index + 1, onResult)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errMsg = when (error) {
                        1 -> "CAMERA_IN_USE"
                        2 -> "MAX_CAMERAS_IN_USE"
                        3 -> "CAMERA_DISABLED"
                        4 -> "CAMERA_DEVICE"
                        5 -> "CAMERA_SERVICE"
                        else -> "UNKNOWN_$error"
                    }
                    Log.e(TAG, "Camera error: $errMsg on ID=$cameraId")
                    try { camera.close() } catch (_: Exception) {}
                    if (!finished) {
                        finished = true
                        cleanup()
                        tryCamera(context, manager, candidates, index + 1, onResult)
                    }
                }
            }

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cleanup()
                onResult(null, "No CAMERA permission")
                return
            }
            manager.openCamera(cameraId, cb, handler)
        } catch (e: Exception) {
            cameraBusy.set(false)
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
