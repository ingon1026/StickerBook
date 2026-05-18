package com.k3i.stickerbook.ar

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Convert ImageProxy (YUV_420_888) Y plane to single-channel OpenCV Mat (grayscale).
 * ORB only needs grayscale; we skip U/V planes for speed.
 */
fun yPlaneToGrayMat(image: ImageProxy): Mat {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val bytes = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(bytes)
    } else {
        var off = 0
        val rowBuf = ByteArray(rowStride)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(rowBuf, 0, rowStride)
            for (col in 0 until width) bytes[off++] = rowBuf[col * pixelStride]
        }
    }
    val mat = Mat(height, width, CvType.CV_8UC1)
    mat.put(0, 0, bytes)
    return mat
}
