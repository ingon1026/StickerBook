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

                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                        var sample = 1
                        while (maxDim / sample > MAX_DIM) sample *= 2

                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)

                        val rot = image.imageInfo.rotationDegrees
                        if (rot != 0) {
                            val m = Matrix().apply { postRotate(rot.toFloat()) }
                            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            if (rotated !== bmp) bmp.recycle()
                            bmp = rotated
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

    companion object {
        private const val MAX_DIM = 2048
    }
}
