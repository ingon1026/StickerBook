package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object MaskRefiner {

    private const val TAG = "MaskRefiner"
    private const val GRAYSCALE_THRESHOLD = 100.0
    private const val CLOSE_KSIZE_LARGE = 81.0
    private const val CLOSE_KSIZE_SMALL = 5.0

    private val nativeReady: Boolean by lazy {
        OpenCVLoader.initLocal().also {
            if (!it) Log.e(TAG, "OpenCVLoader.initLocal() failed")
        }
    }

    fun refine(image: Bitmap, bbox: RectF, mask: Bitmap): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)

        if (!nativeReady) {
            Log.w(TAG, "OpenCV native unavailable; returning scaled raw mask")
            return Bitmap.createScaledBitmap(mask, w, h, true)
        }
        return refineWithOpenCv(image, left, top, w, h)
    }

    private fun refineWithOpenCv(image: Bitmap, left: Int, top: Int, w: Int, h: Int): Bitmap {
        val roi = Bitmap.createBitmap(image, left, top, w, h)
        val roiMat = Mat()
        val gray = Mat()
        val binMask = Mat()
        val closedBin = Mat()
        val closeKernelLarge = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(CLOSE_KSIZE_LARGE, CLOSE_KSIZE_LARGE),
        )
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val resultMask = Mat.zeros(h, w, CvType.CV_8UC1)
        val closed = Mat()
        val closeKernelSmall = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(CLOSE_KSIZE_SMALL, CLOSE_KSIZE_SMALL),
        )
        val outBgra = Mat(h, w, CvType.CV_8UC4)
        val white = Mat(h, w, CvType.CV_8UC1)

        try {
            Utils.bitmapToMat(roi, roiMat)
            Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_BGRA2GRAY)
            Imgproc.threshold(gray, binMask, GRAYSCALE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.morphologyEx(binMask, closedBin, Imgproc.MORPH_CLOSE, closeKernelLarge)
            Imgproc.findContours(
                closedBin, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
            if (largest != null) {
                Imgproc.drawContours(resultMask, listOf(largest), -1, Scalar(255.0), -1)
            }
            Imgproc.morphologyEx(resultMask, closed, Imgproc.MORPH_CLOSE, closeKernelSmall)

            white.setTo(Scalar(255.0))
            Core.merge(listOf(white, white, white, closed), outBgra)
            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outBgra, outBmp)

            Log.i(
                TAG,
                "refined ${w}x${h} mask, ${contours.size} contours, picked area=${largest?.let { Imgproc.contourArea(it) } ?: 0.0}",
            )
            return outBmp
        } finally {
            roi.recycle()
            roiMat.release(); gray.release(); binMask.release(); closedBin.release()
            closeKernelLarge.release(); closeKernelSmall.release()
            hierarchy.release(); contours.forEach { it.release() }
            resultMask.release(); closed.release(); white.release(); outBgra.release()
        }
    }
}
