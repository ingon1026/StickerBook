package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object MaskRefiner {

    private const val TAG = "MaskRefiner"
    private const val GRAYSCALE_THRESHOLD = 100.0
    private const val DILATE_KSIZE = 5.0
    private const val CLOSE_KSIZE = 5.0

    fun refine(image: Bitmap, bbox: RectF, mask: Bitmap): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)

        return try {
            refineWithOpenCv(image, left, top, w, h, mask)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "OpenCV native unavailable; returning scaled raw mask", e)
            Bitmap.createScaledBitmap(mask, w, h, true)
        }
    }

    private fun refineWithOpenCv(image: Bitmap, left: Int, top: Int, w: Int, h: Int, mask: Bitmap): Bitmap {
        val roi = Bitmap.createBitmap(image, left, top, w, h)
        val roiMat = Mat()
        Utils.bitmapToMat(roi, roiMat)
        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_BGRA2GRAY)

        val binMask = Mat()
        Imgproc.threshold(gray, binMask, GRAYSCALE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)

        val scaledMaskBmp = Bitmap.createScaledBitmap(mask, w, h, true)
        val scaledMaskMat = Mat()
        Utils.bitmapToMat(scaledMaskBmp, scaledMaskMat)
        val maskChannels = mutableListOf<Mat>()
        Core.split(scaledMaskMat, maskChannels)
        val maskAlpha = if (maskChannels.size >= 4) maskChannels[3] else maskChannels[0]
        val seed = Mat()
        Imgproc.threshold(maskAlpha, seed, 127.0, 255.0, Imgproc.THRESH_BINARY)
        val expandedMask = Mat()
        val dilateKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(DILATE_KSIZE, DILATE_KSIZE),
        )
        Imgproc.dilate(seed, expandedMask, dilateKernel)

        val combinedMask = Mat()
        Core.bitwise_and(binMask, expandedMask, combinedMask)

        val finalMask = Mat()
        Core.bitwise_or(seed, combinedMask, finalMask)
        val closed = Mat()
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(CLOSE_KSIZE, CLOSE_KSIZE),
        )
        Imgproc.morphologyEx(finalMask, closed, Imgproc.MORPH_CLOSE, closeKernel)

        val outBgra = Mat(h, w, CvType.CV_8UC4)
        val white = Mat(h, w, CvType.CV_8UC1)
        white.setTo(org.opencv.core.Scalar(255.0))
        Core.merge(listOf(white, white, white, closed), outBgra)
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outBgra, outBmp)

        roiMat.release(); gray.release(); binMask.release()
        scaledMaskMat.release(); maskChannels.forEach { it.release() }
        seed.release(); expandedMask.release(); combinedMask.release()
        finalMask.release(); closed.release(); white.release(); outBgra.release()

        Log.i(TAG, "refined ${w}x${h} mask")
        return outBmp
    }
}
