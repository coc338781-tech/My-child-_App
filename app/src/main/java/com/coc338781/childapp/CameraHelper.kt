package com.coc338781.childapp

import android.content.Context
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

    fun takePhoto(context: Context, backCamera: Boolean, onResult: (File?) -> Unit) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findCamera(manager, backCamera)
            if (cameraId == null) { onResult(null); return }

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            val thread = HandlerThread("Cam").apply { start() }
            val handler = Handler(thread.looper)
            var device: CameraDevice? = null

            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { it.write(bytes) }
                    img.close()
                    device?.close()
                    r.close()
                    thread.quitSafely()
                    onResult(file)
                } catch (e: Exception) {
                    img.close()
                    onResult(null)
                }
            }, handler)

            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(reader.surface)
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try { session.capture(req.build(), null, handler) }
                                    catch (e: Exception) { onResult(null) }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { onResult(null) }
                            }, handler)
                    } catch (e: Exception) { onResult(null) }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); onResult(null) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); onResult(null) }
            }

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onResult(null); return
            }
            manager.openCamera(cameraId, cb, handler)
        } catch (e: Exception) {
            Log.e(TAG, "err", e)
            onResult(null)
        }
    }

    private fun findCamera(manager: CameraManager, back: Boolean): String? {
        val target = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        for (id in manager.cameraIdList) {
            if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == target) return id
        }
        return null
    }
}
