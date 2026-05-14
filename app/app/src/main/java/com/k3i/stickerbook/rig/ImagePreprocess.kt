package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object ImagePreprocess {

    // BGR mean (subtract; std=1, no divide). Matches AD mmdet export config.
    private val BGR_MEAN = floatArrayOf(103.53f, 116.28f, 123.675f)

    /**
     * Converts a Bitmap to NCHW FloatBuffer in BGR mean-subtracted form.
     * Returns (buffer, height, width). Layout [1, 3, H, W], channel order B, G, R.
     */
    fun toNchwTensor(bitmap: Bitmap): Triple<FloatBuffer, Int, Int> {
        val h = bitmap.height
        val w = bitmap.width
        val pixels = IntArray(h * w)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Plane order: B, G, R (CHW)
        for (c in 0..2) {
            val mean = BGR_MEAN[c]
            for (i in 0 until h * w) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> p and 0xFF              // B
                    1 -> (p shr 8) and 0xFF      // G
                    else -> (p shr 16) and 0xFF  // R
                }
                buf.put(v.toFloat() - mean)
            }
        }
        buf.rewind()
        return Triple(buf, h, w)
    }
}
