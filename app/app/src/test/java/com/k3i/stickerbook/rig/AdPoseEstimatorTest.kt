package com.k3i.stickerbook.rig

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdPoseEstimatorTest {

    @Test
    fun `crop bbox of full image returns same image-space coords`() {
        val bbox = RectF(0f, 0f, 100f, 100f)
        val imageW = 100
        val imageH = 100
        val cropW = 192
        val cropH = 256

        val cropPx = 96f
        val cropPy = 128f
        val (imgX, imgY) = AdPoseEstimator.unprojectFromCrop(
            cropX = cropPx, cropY = cropPy,
            bbox = bbox,
            cropW = cropW, cropH = cropH,
            paddingRatio = 1.25f,
            imageW = imageW, imageH = imageH,
        )
        // 100x100 image, bbox = entire image, 1.25 padding makes bbox 125x125 centered at (50,50).
        // crop 192x256 of that → center (96, 128) maps to image center (50, 50).
        assertEquals(50f, imgX, 0.5f)
        assertEquals(50f, imgY, 0.5f)
    }

    @Test
    fun `crop bbox in upper-left maps keypoint correctly`() {
        val bbox = RectF(10f, 20f, 50f, 80f)
        val (imgX, imgY) = AdPoseEstimator.unprojectFromCrop(
            cropX = 0f, cropY = 0f,  // crop top-left
            bbox = bbox, cropW = 192, cropH = 256,
            paddingRatio = 1.25f, imageW = 200, imageH = 200,
        )
        val (cropBackX, cropBackY) = AdPoseEstimator.projectToCrop(
            imageX = imgX, imageY = imgY,
            bbox = bbox, cropW = 192, cropH = 256,
            paddingRatio = 1.25f,
        )
        assertEquals(0f, cropBackX, 1.0f)
        assertEquals(0f, cropBackY, 1.0f)
    }
}
