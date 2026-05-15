package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkeletonOverlayTest {

    @Test
    fun draws_landmark_dots_returns_bitmap() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)

        val landmarks = listOf(
            Landmark(x = 100f, y = 100f, z = 0f, visibility = 1f, presence = 1f),
        )
        val skeleton = SkeletonData(landmarks = landmarks, imageWidth = 200, imageHeight = 200)
        val out = SkeletonOverlay.draw(bmp, skeleton)

        // Output is a valid bitmap with correct dimensions
        assertEquals(200, out.width)
        assertEquals(200, out.height)
        assert(!out.isRecycled)
    }

    @Test
    fun empty_landmarks_returns_unchanged_size() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val skeleton = SkeletonData(landmarks = emptyList(), imageWidth = 64, imageHeight = 48)
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(64, out.width)
        assertEquals(48, out.height)
    }

    @Test
    fun `ad-coco-17 skeleton draws 17 dots only`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val landmarks = List(17) { i ->
            Landmark(
                x = (i * 5 + 10).toFloat(),
                y = (i * 5 + 10).toFloat(),
                z = 0f,
                visibility = 0.9f,
                presence = 0.9f,
            )
        }
        val skeleton = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = landmarks,
            imageWidth = 100,
            imageHeight = 100,
        )
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        // Verify output is valid bitmap
        assert(!out.isRecycled)
    }

    @Test
    fun `coco-17 backend draws correctly with 17 landmarks`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val landmarks = List(17) { i ->
            Landmark(
                x = (i * 5 + 10).toFloat(),
                y = (i * 5 + 10).toFloat(),
                z = 0f,
                visibility = 0.9f,
                presence = 0.9f,
            )
        }
        val skeleton = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = landmarks,
            imageWidth = 100,
            imageHeight = 100,
        )
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        // Verify output is a valid, not-recycled bitmap
        assert(!out.isRecycled)
    }

    @Test
    fun `empty landmarks returns unchanged bitmap`() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val skeleton = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = emptyList(),
            imageWidth = 50,
            imageHeight = 50,
        )
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(Color.WHITE, out.getPixel(0, 0))
    }
}
