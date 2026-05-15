package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ArapRiggerTest {

    private fun fakeDetection(image: Bitmap): Detection {
        val bbox = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        return Detection(bbox = bbox, mask = mask, score = 0.9f)
    }

    @Test
    fun `rig produces 30 frame pngs and a 30 frame RigResult`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val pose = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark((10 + it * 10).toFloat(), (10 + it * 5).toFloat(),
                0f, 0.9f, 0.9f) },
            imageWidth = 200, imageHeight = 200,
        )
        val rigger = ArapRigger.withStubs(
            context = ctx,
            detect = { _: Bitmap -> listOf(fakeDetection(image)) },
            estimate = { _: Bitmap, _: RectF? -> pose },
            motionSource = MotionStub(),
        )
        val result = rigger.rig(image, "wave")
        assertEquals(30, result.frameCount)
        assertEquals(30, result.fps)
        val framesDir = File(ctx.filesDir, "stickerbook_assets/${result.framesDir}")
        assertTrue(framesDir.isDirectory)
        for (i in 1..30) {
            val f = File(framesDir, "${i.toString().padStart(4, '0')}.png")
            assertTrue("frame $i missing at $f", f.isFile)
        }
    }

    @Test
    fun `rig writes skeleton json with ad-coco-17 backend`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pose = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark(it * 4f, it * 4f, 0f, 0.5f, 0.5f) },
            imageWidth = 100, imageHeight = 100,
        )
        val rigger = ArapRigger.withStubs(
            context = ctx,
            detect = { _: Bitmap -> listOf(fakeDetection(image)) },
            estimate = { _: Bitmap, _: RectF? -> pose },
            motionSource = MotionStub(),
        )
        val result = rigger.rig(image, "wave")
        assertTrue(result.skeletonPath != null)
        val skel = File(ctx.filesDir, "stickerbook_assets/${result.skeletonPath}")
        assertTrue(skel.isFile)
        val text = skel.readText()
        assertTrue("ad-coco-17" in text)
    }
}
