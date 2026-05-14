package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

data class Detection(
    val bbox: RectF,
    val mask: Bitmap,
    val score: Float,
)

object MaskPostprocess {

    /**
     * Decodes MMDeploy ONNX output (dets, masks) into Detection list.
     * - dets: flat float array, [N, 5] (x1, y1, x2, y2, score)
     * - masks: flat float array, [N, maskHeight, maskWidth] (binary 0/1 or float 0-1)
     * Mask is returned as a small ARGB_8888 Bitmap of size (maskWidth, maskHeight);
     * downstream code should scale it to bbox size.
     */
    fun decode(
        dets: FloatArray,
        masks: FloatArray,
        maskHeight: Int,
        maskWidth: Int,
        scoreThreshold: Float = 0.5f,
    ): List<Detection> {
        if (dets.isEmpty()) return emptyList()
        val n = dets.size / 5
        val results = mutableListOf<Detection>()
        for (i in 0 until n) {
            val off = i * 5
            val score = dets[off + 4]
            if (score < scoreThreshold) continue
            val bbox = RectF(dets[off], dets[off + 1], dets[off + 2], dets[off + 3])
            val mask = if (masks.isNotEmpty() && maskHeight > 0 && maskWidth > 0) {
                decodeMask(masks, i, maskHeight, maskWidth)
            } else {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            results.add(Detection(bbox, mask, score))
        }
        return results
    }

    private fun decodeMask(masks: FloatArray, index: Int, h: Int, w: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val start = index * h * w
        val pixels = IntArray(h * w)
        for (j in 0 until h * w) {
            val v = masks[start + j]
            val alpha = if (v > 0.5f) 255 else 0
            pixels[j] = Color.argb(alpha, 0, 0, 0)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
