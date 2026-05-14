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
class StubRiggerTest {

    @Test
    fun stub_writes_files_and_returns_rig_result() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val rigger = StubRigger(ctx)
        val r = rigger.rig(bitmap, "dance_1")

        assertTrue(r.framesDir.startsWith("stickers/stub_"))
        assertEquals(1, r.frameCount)
        assertEquals(64, r.width)
        assertEquals(64, r.height)

        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
        assertTrue(File(root, r.texturePath).isFile)
        assertTrue(File(root, r.gifPath).isFile)
        assertTrue(File(root, r.sourcePath).isFile)
    }
}
