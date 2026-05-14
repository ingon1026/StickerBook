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
        val skeleton = SkeletonData(landmarks, 200, 200)
        val out = SkeletonOverlay.draw(bmp, skeleton)

        // Output is a valid bitmap with correct dimensions
        assertEquals(200, out.width)
        assertEquals(200, out.height)
        assert(!out.isRecycled)
    }

    @Test
    fun empty_landmarks_returns_unchanged_size() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val skeleton = SkeletonData(emptyList(), 64, 48)
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(64, out.width)
        assertEquals(48, out.height)
    }
}
