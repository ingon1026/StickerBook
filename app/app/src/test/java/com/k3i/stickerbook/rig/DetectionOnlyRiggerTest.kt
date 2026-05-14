package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DetectionOnlyRiggerTest {

    @Test
    fun returns_stub_when_no_detection() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val rigger = DetectionOnlyRigger.withDetector(
            ctx,
            detect = { _ -> emptyList() },
        )
        val r = rigger.rig(bitmap, "dance_1")

        // Falls back to a single static frame (raw bitmap) when no detection
        assertEquals(1, r.frameCount)
        assertTrue(r.framesDir.startsWith("stickers/det_"))
        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
        assertTrue(File(root, r.texturePath).isFile)
        assertTrue(File(root, r.sourcePath).isFile)
    }
}
