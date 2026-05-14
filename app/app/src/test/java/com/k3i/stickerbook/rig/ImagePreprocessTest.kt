package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImagePreprocessTest {

    @Test
    fun emits_nchw_float_buffer_with_expected_shape() {
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val (buffer, h, w) = ImagePreprocess.toNchwTensor(bmp)
        assertEquals(24, h)
        assertEquals(32, w)
        assertEquals(1 * 3 * 24 * 32, buffer.remaining())
    }

    @Test
    fun applies_bgr_mean_subtract() {
        // Fill with mid gray (128, 128, 128) on R/G/B
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(128, 128, 128))
        val (buffer, _, _) = ImagePreprocess.toNchwTensor(bmp)
        val arr = FloatArray(buffer.remaining()).also { buffer.get(it) }
        // BGR mean = [103.53, 116.28, 123.675]
        // First channel (B): 128 - 103.53 = 24.47
        // Second channel (G): 128 - 116.28 = 11.72
        // Third channel (R): 128 - 123.675 = 4.325
        // Single-channel plane = h*w = 4 pixels
        val planeSize = 4
        assertEquals(24.47f, arr[0], 0.01f)
        assertEquals(11.72f, arr[planeSize], 0.01f)
        assertEquals(4.325f, arr[planeSize * 2], 0.01f)
    }

    @Test
    fun channel_order_is_bgr_not_rgb() {
        // Pure red pixel (R=200, G=0, B=0) → after BGR mean-subtract:
        // B channel: 0 - 103.53 = -103.53
        // G channel: 0 - 116.28 = -116.28
        // R channel: 200 - 123.675 = 76.325
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(200, 0, 0))
        val (buffer, _, _) = ImagePreprocess.toNchwTensor(bmp)
        val arr = FloatArray(buffer.remaining()).also { buffer.get(it) }
        assertEquals(-103.53f, arr[0], 0.01f)
        assertEquals(-116.28f, arr[1], 0.01f)
        assertEquals(76.325f, arr[2], 0.01f)
    }
}
