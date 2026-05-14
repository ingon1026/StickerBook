package com.k3i.stickerbook.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executors

class ImageCaptureController {
    val useCase: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    suspend fun captureBitmap(): Bitmap = suspendCoroutine { cont ->
        useCase.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val rot = image.imageInfo.rotationDegrees
                        if (rot != 0) {
                            val m = Matrix().apply { postRotate(rot.toFloat()) }
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        }
                        cont.resume(bmp)
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    cont.resumeWithException(exc)
                }
            },
        )
    }
}
