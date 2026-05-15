package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PoseDetectionRiggerTest {

    @Test
    fun saves_frames_and_skeleton_json() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

        val dummyLandmarks = List(33) { i ->
            Landmark(x = i.toFloat(), y = i.toFloat() * 2f, z = 0f, visibility = 1f, presence = 1f)
        }
        val rigger = PoseDetectionRigger.withStubs(
            ctx,
            detect = { _ -> emptyList() },
            estimate = { _ -> SkeletonData(landmarks = dummyLandmarks, imageWidth = 128, imageHeight = 128) },
        )
        val r = rigger.rig(bitmap, "dance_1")

        assertEquals(1, r.frameCount)
        assertTrue(r.framesDir.startsWith("stickers/pose_"))
        assertNotNull(r.skeletonPath)

        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
        assertTrue(File(root, r.skeletonPath!!).isFile)
        val content = File(root, r.skeletonPath!!).readText()
        assertTrue(content.contains("landmarks"))
    }

    @Test
    fun handles_pose_estimator_failure_gracefully() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val rigger = PoseDetectionRigger.withStubs(
            ctx,
            detect = { _ -> emptyList() },
            estimate = { _ -> throw RuntimeException("simulated pose failure") },
        )
        val r = rigger.rig(bitmap, "m")
        assertEquals(1, r.frameCount)
        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
    }
}
